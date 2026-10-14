package com.rhythm.los.party;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.common.Mask;
import com.rhythm.los.consent.Consent;
import com.rhythm.los.consent.ConsentRepository;
import com.rhythm.los.document.DocumentReader;
import com.rhythm.los.document.DocumentReading;
import com.rhythm.los.document.NameMatch;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.integration.VendorModels.BureauReport;
import com.rhythm.los.integration.VendorModels.KycBundle;
import com.rhythm.los.integration.Vendors;
import com.rhythm.los.org.Role;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Co-applicants and guarantors (FSD: joint borrowers). Who may be on a loan comes from the product's party rules; each
 * person gives their own consent (by OTP on their own mobile, in the branch or through an invite link), is KYC-checked
 * and bureau-checked on their own, and signs on their own before disbursement.
 */
@Service
public class PartyService implements com.rhythm.los.application.SubmitCheck, com.rhythm.los.decision.DecisionService.PartyGate {
    static final Set<String> DOC_TYPES = Set.of("PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "INCOME_PROOF");
    static final List<String> OVD = List.of("AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT");
    static final java.util.regex.Pattern PAN = java.util.regex.Pattern.compile("[A-Z]{3}P[A-Z][0-9]{4}[A-Z]");
    static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN", "DISBURSED");
    static final Set<String> AFTER_SANCTION = Set.of("SANCTIONED", "KFS_ACCEPTED");
    static final int MAX_OTP_ATTEMPTS = 5;
    static final int MAX_OPEN_GUARANTEES = 2;
    private static final SecureRandom RNG = new SecureRandom();

    private final ApplicationService apps;
    private final ApplicationPartyRepository parties;
    private final PartyDocumentRepository docs;
    private final PartyRequirementRepository requirements;
    private final ProductService products;
    private final PolicyService policy;
    private final AuditService audit;
    private final StorageService storage;
    private final DocumentReader reader;
    private final IntegrationGateway gateway;
    private final Vendors.KycProvider kyc;
    private final Vendors.CreditBureau bureau;
    private final ConsentRepository consents;
    private final com.rhythm.los.collateral.CollateralRepository collaterals;
    private final com.rhythm.los.collateral.CollateralDocumentRepository collateralDocs;
    private final com.rhythm.los.decision.DecisionRecordRepository decisions;
    private final String demoOtp;
    private com.rhythm.los.flow.WorkflowService workflow;

    public PartyService(ApplicationService apps, ApplicationPartyRepository parties, PartyDocumentRepository docs, PartyRequirementRepository requirements,
                        ProductService products, PolicyService policy, AuditService audit, StorageService storage, DocumentReader reader,
                        IntegrationGateway gateway, Vendors.KycProvider kyc, Vendors.CreditBureau bureau, ConsentRepository consents,
                        com.rhythm.los.collateral.CollateralRepository collaterals, com.rhythm.los.collateral.CollateralDocumentRepository collateralDocs,
                        com.rhythm.los.decision.DecisionRecordRepository decisions,
                        @Value("${rhythm.borrower.demo-otp:123456}") String demoOtp) {
        this.apps = apps;
        this.parties = parties;
        this.docs = docs;
        this.requirements = requirements;
        this.products = products;
        this.policy = policy;
        this.audit = audit;
        this.storage = storage;
        this.reader = reader;
        this.gateway = gateway;
        this.kyc = kyc;
        this.bureau = bureau;
        this.consents = consents;
        this.collaterals = collaterals;
        this.collateralDocs = collateralDocs;
        this.decisions = decisions;
        this.demoOtp = demoOtp;
    }

    private com.rhythm.los.vkyc.VkycSessionRepository vkycSessions;

    @org.springframework.beans.factory.annotation.Autowired
    void setVkyc(com.rhythm.los.vkyc.VkycSessionRepository r) { this.vkycSessions = r; }

    /** The product's video KYC settings (pinned version, else live product, else defaults). */
    public com.rhythm.los.vkyc.VkycPolicy vkycPolicy(LoanApplication a) {
        String json = apps.product(a).getVkycPolicy();
        if (json == null) json = products.live(a.getProductCode()).getVkycPolicy();
        return com.rhythm.los.vkyc.VkycPolicy.parse(json);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setWorkflow(@org.springframework.context.annotation.Lazy com.rhythm.los.flow.WorkflowService w) { this.workflow = w; }

    // ---------------------------------------------------------------- rules and views

    /** The rules of the product version the application was created on; the live product's when that version had none. */
    public PartyRules rules(LoanApplication a) {
        Product p = apps.product(a);
        String json = p.getPartyRules();
        if (json == null) {
            Product live = products.live(a.getProductCode());
            if (live.getPartyRulesSince() != null && a.getCreatedAt() != null && a.getCreatedAt().isBefore(live.getPartyRulesSince())) return PartyRules.DEFAULT;
            json = live.getPartyRules();
        }
        return PartyRules.parse(json);
    }

    public List<ApplicationParty> list(Long appId) { return parties.findByApplicationIdOrderByIdAsc(appId); }

    public List<ApplicationParty> active(Long appId) { return list(appId).stream().filter(ApplicationParty::active).toList(); }

    public ApplicationParty get(Long partyId) { return parties.findById(partyId).orElseThrow(() -> ApiException.notFound("Co-applicant or guarantor")); }

    /** Everything the Parties tab shows. */
    public Map<String, Object> view(Long appId) {
        LoanApplication a = apps.get(appId);
        PartyRules r = rules(a);
        List<PartyRules.Issue> issues = issues(a);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApplicationParty p : list(appId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("party", p);
            m.put("mobile", p.maskedMobile());
            m.put("documents", docs.findByPartyIdOrderByIdAsc(p.getId()));
            m.put("kycChecks", p.getKycJson() == null ? List.of() : Json.read(p.getKycJson(), List.class));
            m.put("bureau", p.getBureauJson() == null ? null : Json.read(p.getBureauJson(), Map.class));
            m.put("inviteActive", p.getInviteExpiresAt() != null && p.getInviteExpiresAt().isAfter(Instant.now()));
            m.put("incomeCounted", r.clubs(p.getRole(), p.getRelationship()) && p.isIncomeConsidered());
            m.put("issues", issues.stream().filter(i -> i.message().startsWith(p.getName() + " ")).map(PartyRules.Issue::message).toList());
            m.put("vkyc", vkycSessions == null ? null : vkycSessions.findByApplicationIdOrderByIdDesc(appId).stream().filter(c -> p.getId().equals(c.getPartyId()))
                    .findFirst().map(com.rhythm.los.vkyc.VkycSession::getStatus).orElse(null));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rules", r);
        out.put("parties", rows);
        out.put("requirements", requirements.findByApplicationIdOrderByIdAsc(appId));
        out.put("issues", issues);
        out.put("coOwners", coOwners(a));
        out.put("joint", jointView(a));
        out.put("canChange", editable(a));
        out.put("sanctioned", AFTER_SANCTION.contains(a.getSanctionState()));
        out.put("documentTypes", DOC_TYPES.stream().sorted().toList());
        out.put("documentsRead", reader.extractsFields());
        out.put("relationships", r.relationships());
        out.put("vkycParties", vkycPolicy(a).parties());
        return out;
    }

    static boolean editable(LoanApplication a) {
        return !CLOSED.contains(a.getAppState()) && !AFTER_SANCTION.contains(a.getSanctionState());
    }

    // ---------------------------------------------------------------- adding, changing, removing

    public record PartyRequest(String role, String relationship, String name, String pan, String mobile, String email, LocalDate dob, String gender,
                               String address, String city, String pincode, String occupation, BigDecimal monthlyIncome, BigDecimal existingEmi,
                               Boolean incomeConsidered, Boolean coOwner, Long requirementId) {}

    @Transactional
    public ApplicationParty add(Long appId, PartyRequest r, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!editable(a)) throw ApiException.conflict("NOT_EDITABLE", "Parties can be added until sanction; this application is " + a.getAppState().toLowerCase());
        PartyRules rules = rules(a);
        String role = r.role() == null ? "" : r.role().trim().toUpperCase();
        if (!PartyRules.ROLES.contains(role)) throw ApiException.unprocessable("BAD_ROLE", "Role must be co-applicant or guarantor");
        ApplicationParty p = new ApplicationParty();
        p.setApplicationId(appId);
        p.setRole(role);
        fill(p, r, a, rules, null);
        long same = active(appId).stream().filter(x -> role.equals(x.getRole())).count();
        List<String> problems = rules.admit(role, p.getRelationship(), p.getDob(), p.isIncomeConsidered(), same, a.getTenureMonths(), LocalDate.now());
        if (!problems.isEmpty()) throw ApiException.unprocessable("PARTY_RULE", String.join("; ", problems));
        p.setAddedStage("DRAFT".equals(a.getAppState()) ? "APPLICATION" : "SUBMITTED".equals(a.getAppState()) ? "INTAKE" : "UNDERWRITING");
        p.setAddedBy(by.username());
        if (r.requirementId() != null) {
            PartyRequirement q = requirements.findById(r.requirementId()).filter(x -> x.getApplicationId().equals(appId) && "OPEN".equals(x.getStatus()))
                    .orElseThrow(() -> ApiException.unprocessable("BAD_REQUEST_ID", "That request for a party is not open"));
            if (!q.getRole().equals(role)) throw ApiException.unprocessable("BAD_ROLE", "Credit asked for a " + PartyRules.label(q.getRole()));
            p.setRequirementId(q.getId());
        }
        p = parties.save(p);
        p.setPartyNo("P-" + (list(appId).size()));
        parties.save(p);
        if (p.getRequirementId() != null) {
            PartyRequirement q = requirements.findById(p.getRequirementId()).orElseThrow();
            q.setStatus("MET");
            q.setMetByPartyId(p.getId());
            q.setClosedBy(by.username());
            q.setClosedAt(Instant.now());
            q.setCloseNote(p.getName() + " added as " + PartyRules.label(role));
            requirements.save(q);
        }
        audit.record(by, "party.added", "APPLICATION", a.getAppNo(), appId, p.getPartyNo() + " " + PartyRules.label(role) + " · " + p.getRelationship().toLowerCase()
                + " · PAN " + Mask.pan(p.getPan()) + (p.isIncomeConsidered() ? " · income counted" : ""));
        return p;
    }

    @Transactional
    public ApplicationParty update(Long partyId, PartyRequest r, CurrentUser by) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!editable(a) || !p.active()) throw ApiException.conflict("NOT_EDITABLE", "This party can no longer be changed");
        if (p.getConsentAt() != null) throw ApiException.conflict("CONSENTED", p.getName() + " has already consented: remove and add again to change identity details");
        PartyRules rules = rules(a);
        fill(p, r, a, rules, p.getId());
        long same = active(a.getId()).stream().filter(x -> p.getRole().equals(x.getRole()) && !x.getId().equals(p.getId())).count();
        List<String> problems = rules.admit(p.getRole(), p.getRelationship(), p.getDob(), p.isIncomeConsidered(), same, a.getTenureMonths(), LocalDate.now());
        if (!problems.isEmpty()) throw ApiException.unprocessable("PARTY_RULE", String.join("; ", problems));
        p.setUpdatedAt(Instant.now());
        audit.record(by, "party.updated", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + p.getName());
        return parties.save(p);
    }

    /** Income and existing repayments may be corrected until the decision (credit verifies them against documents). */
    public record IncomeRequest(BigDecimal monthlyIncome, BigDecimal existingEmi, Boolean incomeConsidered, String note) {}

    @Transactional
    public ApplicationParty updateIncome(Long partyId, IncomeRequest r, CurrentUser by) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!editable(a) || !p.active()) throw ApiException.conflict("NOT_EDITABLE", "This party can no longer be changed");
        if (r.note() == null || r.note().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Say what the income was checked against (at least 10 characters)");
        PartyRules rules = rules(a);
        boolean counted = Boolean.TRUE.equals(r.incomeConsidered());
        if (counted && !rules.clubs(p.getRole(), p.getRelationship()))
            throw ApiException.unprocessable("PARTY_RULE", "This product does not count the income of a " + p.getRelationship().toLowerCase().replace('_', ' '));
        if (r.monthlyIncome() != null && r.monthlyIncome().signum() < 0 || r.existingEmi() != null && r.existingEmi().signum() < 0)
            throw ApiException.unprocessable("NEGATIVE", "Income and repayments cannot be negative");
        String before = money(p.getMonthlyIncome()) + (p.isIncomeConsidered() ? " counted" : "");
        p.setMonthlyIncome(r.monthlyIncome());
        p.setExistingEmi(r.existingEmi());
        p.setIncomeConsidered(counted);
        p.setUpdatedAt(Instant.now());
        audit.record(by, "party.income_updated", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + ": " + before + " → " + money(r.monthlyIncome())
                + (counted ? " counted" : "") + " · " + r.note().trim());
        return parties.save(p);
    }

    @Transactional
    public ApplicationParty remove(Long partyId, String reason, CurrentUser by) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!editable(a)) throw ApiException.conflict("NOT_EDITABLE", "Parties cannot be removed after sanction");
        if (!p.active()) throw ApiException.conflict("ALREADY_REMOVED", p.getName() + " is already removed");
        if (reason == null || reason.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        p.setStatus("REMOVED");
        p.setRemovedReason(reason.trim());
        p.setInviteHash(null);
        p.setSessionHash(null);
        p.setUpdatedAt(Instant.now());
        if (p.getRequirementId() != null)
            requirements.findById(p.getRequirementId()).filter(q -> "MET".equals(q.getStatus()) && p.getId().equals(q.getMetByPartyId())).ifPresent(q -> {
                q.setStatus("OPEN");
                q.setMetByPartyId(null);
                q.setClosedAt(null);
                q.setClosedBy(null);
                q.setCloseNote(null);
                requirements.save(q);
            });
        audit.record(by, "party.removed", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + p.getName() + " · " + reason.trim());
        return parties.save(p);
    }

    private void fill(ApplicationParty p, PartyRequest r, LoanApplication a, PartyRules rules, Long self) {
        String name = r.name() == null ? "" : r.name().trim().replaceAll("\\s+", " ");
        if (name.length() < 3 || name.length() > 120) throw ApiException.unprocessable("BAD_NAME", "Full name as on PAN (3 to 120 characters)");
        String pan = r.pan() == null ? "" : r.pan().trim().toUpperCase();
        if (!PAN.matcher(pan).matches()) throw ApiException.unprocessable("BAD_PAN", "PAN must be an individual's PAN, e.g. ABCPK1234L");
        if (pan.equalsIgnoreCase(a.getPan())) throw ApiException.unprocessable("SAME_AS_APPLICANT", "This is the applicant's own PAN");
        if (active(a.getId()).stream().anyMatch(x -> pan.equals(x.getPan()) && !x.getId().equals(self)))
            throw ApiException.conflict("DUPLICATE_PARTY", "A party with PAN " + Mask.pan(pan) + " is already on this application");
        String mobile = r.mobile() == null ? "" : r.mobile().trim();
        if (!mobile.matches("[6-9][0-9]{9}")) throw ApiException.unprocessable("BAD_MOBILE", "Mobile must be 10 digits starting 6 to 9");
        // consent and signatures are by OTP on the person's own phone, so it cannot be the applicant's or another party's
        if (mobile.equals(a.getMobile())) throw ApiException.unprocessable("SAME_MOBILE", "Use the person's own mobile: OTPs for their consent go to it, not to the applicant's");
        if (active(a.getId()).stream().anyMatch(x -> mobile.equals(x.getMobile()) && !x.getId().equals(self)))
            throw ApiException.unprocessable("SAME_MOBILE", "Another party on this application has that mobile");
        if (r.dob() == null) throw ApiException.unprocessable("DOB_REQUIRED", "Date of birth is required");
        String rel = r.relationship() == null ? "" : r.relationship().trim().toUpperCase();
        if (!PartyRules.ALL_RELATIONSHIPS.contains(rel)) throw ApiException.unprocessable("BAD_RELATIONSHIP", "Relationship must be one of " + PartyRules.ALL_RELATIONSHIPS);
        if (r.monthlyIncome() != null && r.monthlyIncome().signum() < 0 || r.existingEmi() != null && r.existingEmi().signum() < 0)
            throw ApiException.unprocessable("NEGATIVE", "Income and repayments cannot be negative");
        if (r.pincode() != null && !r.pincode().isBlank() && !r.pincode().matches("[1-9][0-9]{5}")) throw ApiException.unprocessable("BAD_PIN", "PIN code must be 6 digits");
        p.setName(name);
        p.setPan(pan);
        p.setMobile(mobile);
        p.setEmail(blank(r.email()));
        p.setDob(r.dob());
        p.setGender(blank(r.gender()) == null ? null : r.gender().trim().toUpperCase());
        p.setAddress(blank(r.address()));
        p.setCity(blank(r.city()));
        p.setPincode(blank(r.pincode()));
        p.setOccupation(blank(r.occupation()));
        p.setRelationship(rel);
        p.setMonthlyIncome(r.monthlyIncome());
        p.setExistingEmi(r.existingEmi());
        boolean counted = Boolean.TRUE.equals(r.incomeConsidered());
        if (counted && (r.monthlyIncome() == null || r.monthlyIncome().signum() <= 0))
            throw ApiException.unprocessable("INCOME_REQUIRED", "Enter the monthly income to count it");
        p.setIncomeConsidered(counted);
        p.setCoOwner(Boolean.TRUE.equals(r.coOwner()));
    }

    // ---------------------------------------------------------------- invite link and OTP

    /** A link the person opens on their own phone: OTP, consent, documents, and later their signature. Valid 7 days. */
    @Transactional
    public Map<String, Object> invite(Long partyId, CurrentUser by) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!p.active() || CLOSED.contains(a.getAppState())) throw ApiException.conflict("NOT_ACTIVE", "This party is not active");
        String token = token();
        p.setInviteHash(sha(token));
        p.setInviteExpiresAt(Instant.now().plus(Duration.ofDays(7)));
        p.setInvitedAt(Instant.now());
        if ("ADDED".equals(p.getStatus())) p.setStatus("INVITED");
        parties.save(p);
        audit.record(by, "party.invited", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + p.getName() + " · link sent to " + p.maskedMobile());
        String tenant = com.rhythm.los.tenant.TenantContext.code();
        String path = "/party/" + token + (com.rhythm.los.tenant.TenantContext.isDefault() ? "" : "?t=" + tenant);
        return Map.of("path", path, "expiresAt", p.getInviteExpiresAt(), "mobile", p.maskedMobile(),
                "sms", "Rhythm: " + a.getApplicantName().split(" ")[0] + " has named you as " + (PartyRules.GUARANTOR.equals(p.getRole()) ? "a guarantor" : "a co-applicant")
                        + " for loan " + a.getAppNo() + ". Review and give consent: <link>. Valid 7 days.");
    }

    /** Sends an OTP to the party's own mobile (demo OTP until SMS is integrated). */
    @Transactional
    public Map<String, Object> sendOtp(Long partyId, String actor) {
        ApplicationParty p = get(partyId);
        if (!p.active()) throw ApiException.conflict("NOT_ACTIVE", "This party is not active");
        if (p.getOtpExpiresAt() != null && p.getOtpExpiresAt().minus(Duration.ofMinutes(5)).plusSeconds(20).isAfter(Instant.now()))
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "OTP_TOO_SOON", "An OTP was just sent. Wait 20 seconds before asking again.");
        String code = demoOtp == null || demoOtp.isBlank() ? String.format("%06d", RNG.nextInt(1_000_000)) : demoOtp;
        p.setOtpHash(sha(p.getId() + ":" + code));
        p.setOtpExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        p.setOtpAttempts(0);
        parties.save(p);
        audit.record(actor, "PARTY", "party.otp_sent", "APPLICATION", p.getPartyNo(), p.getApplicationId(), "OTP to " + p.maskedMobile());
        return Map.of("sent", true, "mobile", p.maskedMobile(), "expiresInSeconds", 300, "demo", demoOtp != null && !demoOtp.isBlank());
    }

    void checkOtp(ApplicationParty p, String code) {
        if (p.getOtpHash() == null || p.getOtpExpiresAt() == null || Instant.now().isAfter(p.getOtpExpiresAt()))
            throw ApiException.unprocessable("OTP_EXPIRED", "Send an OTP to " + p.maskedMobile() + " first (OTPs last 5 minutes)");
        if (p.getOtpAttempts() >= MAX_OTP_ATTEMPTS) throw ApiException.unprocessable("TOO_MANY_ATTEMPTS", "Too many wrong attempts. Send a new OTP.");
        p.setOtpAttempts(p.getOtpAttempts() + 1);
        if (code == null || !sha(p.getId() + ":" + code.trim()).equals(p.getOtpHash())) {
            parties.save(p);
            throw ApiException.unprocessable("BAD_OTP", "The OTP is not correct");
        }
        p.setOtpHash(null);
        p.setOtpExpiresAt(null);
    }

    /** The party behind an invite link, if the link is valid. */
    public ApplicationParty byInvite(String token) {
        if (token == null || token.length() < 20) throw ApiException.notFound("Invite");
        ApplicationParty p = parties.findByInviteHash(sha(token)).orElseThrow(() -> ApiException.notFound("Invite"));
        if (!p.active() || p.getInviteExpiresAt() == null || Instant.now().isAfter(p.getInviteExpiresAt()))
            throw ApiException.conflict("INVITE_EXPIRED", "This link has expired. Ask the lender for a new one.");
        return p;
    }

    /** Invite page: OTP verified, a 30-minute session for this party only. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyInvite(String token, String code) {
        ApplicationParty p = byInvite(token);
        checkOtp(p, code);
        String session = token();
        p.setSessionHash(sha(session));
        p.setSessionExpiresAt(Instant.now().plus(Duration.ofMinutes(30)));
        parties.save(p);
        audit.record("party:" + p.getPartyNo(), "PARTY", "party.signed_in", "APPLICATION", p.getPartyNo(), p.getApplicationId(), "OTP verified on " + p.maskedMobile());
        return Map.of("session", session, "expiresInMinutes", 30);
    }

    public ApplicationParty bySession(String token, String session) {
        ApplicationParty p = byInvite(token);
        if (session == null || p.getSessionHash() == null || !sha(session).equals(p.getSessionHash()) || Instant.now().isAfter(p.getSessionExpiresAt()))
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "Please verify with OTP again");
        return p;
    }

    /** What the person sees on the invite page: nothing about the applicant beyond first name, product and amount. */
    public Map<String, Object> inviteView(ApplicationParty p, boolean signedIn) {
        LoanApplication a = apps.get(p.getApplicationId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("role", p.getRole());
        m.put("relationship", p.getRelationship());
        m.put("mobile", p.maskedMobile());
        m.put("applicant", a.getApplicantName().split(" ")[0]);
        m.put("appNo", a.getAppNo());
        m.put("product", apps.product(a).getName());
        m.put("amount", a.getLoanAmount());
        m.put("tenureMonths", a.getTenureMonths());
        m.put("liability", PartyRules.GUARANTOR.equals(p.getRole())
                ? "As guarantor you agree to repay if the borrower does not. The lender may ask you to pay what is overdue."
                : "As co-applicant you are jointly and severally liable: the lender may recover the whole loan from you or the applicant.");
        m.put("consented", p.getConsentAt() != null);
        m.put("kycState", p.getKycState());
        m.put("signed", p.getSignedAt() != null);
        boolean sanctioned = AFTER_SANCTION.contains(a.getSanctionState());
        m.put("canSign", signedIn && sanctioned && p.getSignedAt() == null && p.getConsentAt() != null);
        if (signedIn) {
            m.put("documents", docs.findByPartyIdOrderByIdAsc(p.getId()).stream().map(d -> Map.of("docType", d.getDocType(), "fileName", d.getFileName(),
                    "status", d.getStatus(), "uploadedAt", d.getUploadedAt())).toList());
            m.put("documentTypes", List.of("PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "INCOME_PROOF"));
            if (sanctioned) m.put("terms", signTerms(p, a));
        }
        return m;
    }

    // ---------------------------------------------------------------- consent

    public record ConsentRequest(String otp, boolean consentKyc, boolean consentBureau, String language) {}

    /** In the branch: the person reads the consent and tells staff the OTP sent to their own phone. */
    @Transactional(noRollbackFor = ApiException.class)
    public ApplicationParty consentInBranch(Long partyId, ConsentRequest r, CurrentUser by) {
        ApplicationParty p = get(partyId);
        checkOtp(p, r.otp());
        return consent(p, r, "Branch (OTP on the party's mobile, " + by.username() + ")", by.username());
    }

    @Transactional
    public ApplicationParty consentByLink(String token, String session, ConsentRequest r) {
        ApplicationParty p = bySession(token, session);
        return consent(p, r, "Invite link (OTP-verified mobile)", "party:" + p.getPartyNo());
    }

    private ApplicationParty consent(ApplicationParty p, ConsentRequest r, String channel, String actor) {
        LoanApplication a = apps.get(p.getApplicationId());
        if (!p.active() || CLOSED.contains(a.getAppState())) throw ApiException.conflict("NOT_ACTIVE", "This party is not active");
        if (!r.consentKyc() || !r.consentBureau()) throw ApiException.unprocessable("CONSENT_MISSING", "Both identity (KYC) and credit bureau consent are needed to be on the loan");
        if (p.getConsentAt() != null) return parties.save(p);
        String lang = r.language() == null || !r.language().matches("en|hi|mr|ta|ml|kn|te|bn|gu") ? "en" : r.language();
        Instant now = Instant.now();
        p.setConsentKyc(true);
        p.setConsentBureau(true);
        p.setConsentAt(now);
        p.setConsentChannel(channel.length() > 20 ? channel.startsWith("Branch") ? "BRANCH_OTP" : "INVITE_LINK" : channel);
        p.setConsentLanguage(lang);
        p.setStatus("CONSENTED");
        p.setUpdatedAt(now);
        String who = p.getName() + " (" + PartyRules.label(p.getRole()) + ", " + p.getPartyNo() + ")";
        consents.save(new Consent(a.getId(), "PARTY_KYC", "Verify the identity of " + who, "PAN and the identity documents " + p.getName() + " uploads",
                channel, lang, now.plus(Duration.ofDays(365))));
        consents.save(new Consent(a.getId(), "PARTY_BUREAU", "Check the credit history of " + who, "One credit report on " + p.getName(),
                channel, lang, now.plus(Duration.ofDays(180))));
        audit.record(actor, "PARTY", "party.consented", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " KYC and bureau consent · " + channel);
        return parties.save(p);
    }

    // ---------------------------------------------------------------- documents

    @Transactional
    public PartyDocument upload(Long partyId, String type, MultipartFile file, String actor) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!p.active() || CLOSED.contains(a.getAppState())) throw ApiException.conflict("NOT_ACTIVE", "This party is not active");
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!DOC_TYPES.contains(t)) throw ApiException.unprocessable("BAD_DOC_TYPE", "Document type must be one of " + new TreeSet<>(DOC_TYPES));
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose a file");
        if (file.getSize() > 10 * 1024 * 1024) throw ApiException.unprocessable("TOO_LARGE", "The file is larger than 10 MB");
        String ct = file.getContentType() == null ? "" : file.getContentType();
        if (!Set.of("application/pdf", "image/jpeg", "image/png").contains(ct)) throw ApiException.unprocessable("BAD_FILE_TYPE", "Upload a PDF, JPG or PNG");
        byte[] bytes;
        try { bytes = file.getBytes(); } catch (java.io.IOException e) { throw ApiException.unprocessable("UNREADABLE", "The file could not be read"); }
        String name = file.getOriginalFilename() == null ? t.toLowerCase() : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._ -]", "_");
        if (name.length() > 150) name = name.substring(name.length() - 150);
        PartyDocument d = new PartyDocument();
        d.setPartyId(p.getId());
        d.setApplicationId(a.getId());
        d.setDocType(t);
        d.setFileName(name);
        d.setContentType(ct);
        d.setSizeBytes(bytes.length);
        d.setSha256(sha(bytes));
        d.setUploadedBy(actor);
        final byte[] content = bytes;
        final String fname = name;
        var out = gateway.call(a.getId(), reader.name(), "party.document.read", () -> reader.read(content, ct, fname, t),
                r -> r.status() + " · " + r.documentType(), null);
        byte[] keep = bytes;
        if (out.ok()) {
            DocumentReading r = out.value();
            Map<String, String> fields = new LinkedHashMap<>();
            if (r.fields() != null) r.fields().forEach((k, v) -> { if (v != null && v.value() != null) fields.put(k, String.valueOf(v.value())); });
            // the Aadhaar number never leaves the AI service unmasked; keep only the last four digits if a number came back
            fields.computeIfPresent("aadhaar", (k, v) -> v.replaceAll("[^0-9]", "").length() >= 4 ? "XXXX-XXXX-" + v.replaceAll("[^0-9]", "").substring(v.replaceAll("[^0-9]", "").length() - 4) : v);
            d.setExtractedJson(Json.write(fields));
            d.setReadConfidence(BigDecimal.valueOf(r.overallConfidence()).setScale(3, java.math.RoundingMode.HALF_UP).min(BigDecimal.ONE));
            d.setStatus("OK".equals(r.status()) ? "VERIFIED" : "UNREADABLE".equals(r.status()) ? "REJECTED" : "NEEDS_REVIEW");
            d.setRemarks(r.reviewReasons() == null || r.reviewReasons().isEmpty() ? null : trim(String.join("; ", r.reviewReasons()), 300));
            if (r.masked() && r.maskedImage() != null) {
                keep = r.maskedImage();
                d.setMasked(true);
                d.setContentType("image/png");
                d.setFileName(name.replaceAll("\\.[A-Za-z0-9]+$", "") + "-masked.png");
            }
        } else {
            d.setStatus("UPLOADED");
            d.setRemarks("Not read yet: " + trim(out.error(), 200));
        }
        if ("AADHAAR".equals(t) && !d.isMasked() && reader.extractsFields()) {
            // an Aadhaar image is stored only when masked; without masking keep nothing but the reading
            d.setStatus("REJECTED");
            d.setRemarks("Aadhaar must be masked (first 8 digits hidden). Upload a masked copy or use DigiLocker.");
            keep = null;
        }
        d.setStoragePath(keep == null ? "-" : storage.store(a.getId(), keep, d.getContentType().equals("application/pdf") ? ".pdf" : d.getContentType().equals("image/png") ? ".png" : ".jpg"));
        d = docs.save(d);
        audit.record(actor, actor.startsWith("party:") ? "PARTY" : "STAFF", "party.document_uploaded", "APPLICATION", a.getAppNo(), a.getId(),
                p.getPartyNo() + " " + t + " · " + d.getStatus());
        // a new identity document re-opens KYC that has not passed yet
        if (OVD.contains(t) || "PAN".equals(t)) {
            if (Set.of("REVIEW", "FAILED").contains(p.getKycState()) && p.getKycReviewedBy() == null) { p.setKycState("NOT_STARTED"); parties.save(p); }
        }
        return d;
    }

    public byte[] documentContent(Long docId) {
        PartyDocument d = docs.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        if ("-".equals(d.getStoragePath())) throw ApiException.notFound("Document image (not kept)");
        return storage.read(d.getStoragePath());
    }

    public PartyDocument document(Long docId) { return docs.findById(docId).orElseThrow(() -> ApiException.notFound("Document")); }

    // ---------------------------------------------------------------- KYC and bureau

    /** Runs KYC and the bureau pull for every party who has consented and is ready; returns what happened. */
    @Transactional
    public List<String> runChecks(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        List<String> log = new ArrayList<>();
        if (CLOSED.contains(a.getAppState()) || "DRAFT".equals(a.getAppState())) return log;
        for (ApplicationParty p : active(appId)) {
            if (p.getConsentAt() == null) continue;
            try {
                if ("NOT_STARTED".equals(p.getKycState())) {
                    String r = runKyc(p, a, by);
                    log.add(p.getName() + ": KYC " + r);
                }
                if ("VERIFIED".equals(p.getKycState()) && Set.of("NOT_FETCHED", "FAILED").contains(p.getBureauState())) {
                    pullBureau(p, a, by);
                    log.add(p.getName() + ": bureau " + p.getBureauState().toLowerCase().replace('_', ' ') + (p.getBureauScore() == null ? "" : " " + p.getBureauScore()));
                }
            } catch (ApiException e) {
                log.add(p.getName() + ": " + e.getMessage());
            }
        }
        return log;
    }

    String runKyc(ApplicationParty p, LoanApplication a, CurrentUser by) {
        PolicyParams pol = policy.liveFor(a.getProductCode(), a.getSegment());
        List<Map<String, Object>> checks = new ArrayList<>();
        double score;
        boolean panMismatch = false;
        if (kyc.needsVerifiedDocuments()) {
            // KYC from the person's own verified documents, the same comparisons as for the applicant
            Map<String, Map<String, String>> byType = new LinkedHashMap<>();
            for (PartyDocument d : docs.findByPartyIdOrderByIdAsc(p.getId()))
                if ("VERIFIED".equals(d.getStatus())) byType.put(d.getDocType(), d.getExtractedJson() == null ? Map.of() : Json.read(d.getExtractedJson(), Map.class));
            String ovdType = OVD.stream().filter(byType::containsKey).findFirst().orElse(null);
            if (!byType.containsKey("PAN") || ovdType == null) {
                p.setKycNote("Waiting for " + (!byType.containsKey("PAN") ? "PAN" : "") + (!byType.containsKey("PAN") && ovdType == null ? " and " : "")
                        + (ovdType == null ? "Aadhaar, voter ID, driving licence or passport" : "") + " (read and verified)");
                parties.save(p);
                return "waiting for documents";
            }
            Map<String, String> pan = byType.get("PAN"), ovd = byType.get(ovdType);
            String panNo = pan.get("pan");
            double id = panNo == null ? 0.5 : panNo.equalsIgnoreCase(p.getPan()) ? 1 : 0;
            panMismatch = panNo != null && !panNo.equalsIgnoreCase(p.getPan());
            checks.add(check("PAN on card matches", id, Mask.pan(p.getPan()), Mask.pan(panNo)));
            double n1 = pan.get("name") == null ? 0.5 : NameMatch.score(p.getName(), pan.get("name"));
            checks.add(check("Name on PAN matches", n1, p.getName(), pan.get("name")));
            String ovdName = "PASSPORT".equals(ovdType) ? join(ovd.get("givenNames"), ovd.get("surname")) : ovd.get("name");
            double n2 = ovdName == null ? 0.5 : NameMatch.score(p.getName(), ovdName);
            checks.add(check("Name on " + ovdType.toLowerCase().replace('_', ' ') + " matches", n2, p.getName(), ovdName));
            String dobRead = pan.get("dob") != null ? pan.get("dob") : ovd.get("dob");
            double dob = dobMatch(p.getDob(), dobRead);
            checks.add(check("Date of birth matches", dob, p.getDob().toString(), dobRead));
            score = 0.35 * Math.min(n1, n2) + 0.25 * id + 0.25 * dob + 0.15 * 0.9;
        } else {
            LoanApplication shadow = shadow(p, a);
            var out = gateway.call(a.getId(), kyc.name(), "party.kyc.verify", () -> kyc.verify(shadow),
                    k -> "name " + k.nameMatch() + ", address " + k.addressMatch(), null);
            if (!out.ok()) throw ApiException.conflict("VENDOR_FAILED", "KYC provider failed: " + out.error());
            KycBundle k = out.value();
            score = DataFetchService.idScore(k);
            panMismatch = k.pan() != null && k.pan().pan() != null && !k.pan().pan().isBlank() && !k.pan().pan().equalsIgnoreCase(p.getPan());
            checks.add(check("PAN valid and matches", k.idMatch(), Mask.pan(p.getPan()), k.pan() == null ? null : Mask.pan(k.pan().pan())));
            checks.add(check("Name matches PAN register", k.nameMatch(), p.getName(), k.pan() == null ? null : k.pan().registeredName()));
            checks.add(check("Date of birth matches", k.dobMatch(), p.getDob().toString(), k.pan() == null ? null : k.pan().dob()));
            checks.add(check("Address matches", k.addressMatch(), p.getCity(), null));
        }
        String to = panMismatch ? "FAILED" : score < pol.kycFail() ? "FAILED" : score < pol.kycReview() ? "REVIEW" : "VERIFIED";
        p.setKycState(to);
        p.setKycScore(BigDecimal.valueOf(score).setScale(3, java.math.RoundingMode.HALF_UP).min(BigDecimal.ONE));
        p.setKycJson(Json.write(checks));
        p.setKycNote(panMismatch ? "PAN on the card does not match the PAN given" : "Identity match " + Math.round(score * 100) + "%");
        p.setStatus("VERIFIED".equals(to) ? "VERIFIED" : "REVIEW".equals(to) ? "REVIEW" : "FAILED");
        p.setUpdatedAt(Instant.now());
        parties.save(p);
        audit.record(by, "party.kyc", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + to + " · " + p.getKycNote());
        return to.toLowerCase() + " (" + Math.round(score * 100) + "%)";
    }

    void pullBureau(ApplicationParty p, LoanApplication a, CurrentUser by) {
        if (!p.isConsentBureau()) throw ApiException.conflict("CONSENT_MISSING", p.getName() + " has not consented to a bureau check");
        LoanApplication shadow = shadow(p, a);
        var out = gateway.call(a.getId(), bureau.name(), "party.bureau.pull", () -> bureau.pull(shadow),
                b -> b.hit() ? "score " + b.score() : "no hit", null);
        if (!out.ok()) {
            p.setBureauState("FAILED");
            parties.save(p);
            throw ApiException.conflict("VENDOR_FAILED", "Bureau failed: " + out.error());
        }
        BureauReport b = out.value();
        p.setBureauState(b.hit() ? "FETCHED" : "NO_HIT");
        p.setBureauScore(b.hit() ? b.score() : null);
        p.setBureauJson(Json.write(b));
        p.setUpdatedAt(Instant.now());
        parties.save(p);
        audit.record(by, "party.bureau", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + (b.hit() ? b.bureau() + " " + b.score() : "no credit record"));
    }

    /** Operations clears or fails a party's KYC review (for example after seeing the original documents). */
    @Transactional
    public ApplicationParty resolveKyc(Long partyId, boolean verified, String note, CurrentUser by) {
        ApplicationParty p = get(partyId);
        LoanApplication a = apps.get(p.getApplicationId());
        if (!"REVIEW".equals(p.getKycState())) throw ApiException.conflict("NOT_IN_REVIEW", p.getName() + "'s KYC is " + p.getKycState().toLowerCase());
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Say what you checked (at least 10 characters)");
        p.setKycState(verified ? "VERIFIED" : "FAILED");
        p.setStatus(verified ? "VERIFIED" : "FAILED");
        p.setKycReviewedBy(by.username());
        p.setKycNote(trim((verified ? "Cleared: " : "Failed: ") + note.trim(), 300));
        parties.save(p);
        audit.record(by, "party.kyc_resolved", "APPLICATION", a.getAppNo(), a.getId(), p.getPartyNo() + " " + p.getKycState() + " · " + note.trim());
        return p;
    }

    /** The party presented to a vendor port that expects an application: their own identity, never the applicant's. */
    static LoanApplication shadow(ApplicationParty p, LoanApplication a) {
        LoanApplication s = new LoanApplication();
        s.setApplicantName(p.getName());
        s.setPan(p.getPan());
        s.setMobile(p.getMobile());
        s.setDob(p.getDob());
        s.setCity(p.getCity() == null ? a.getCity() : p.getCity());
        s.setPincode(p.getPincode() == null ? a.getPincode() : p.getPincode());
        s.setDeclaredMonthlyIncome(p.getMonthlyIncome() == null ? BigDecimal.ZERO : p.getMonthlyIncome());
        s.setSegment(a.getSegment());
        s.setProductCode(a.getProductCode());
        return s;
    }

    // ---------------------------------------------------------------- requests from credit

    public record RequirementRequest(String role, String reason) {}

    @Transactional
    public PartyRequirement request(Long appId, RequirementRequest r, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!Set.of(Role.CREDIT_OFFICER, Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN).contains(by.role()))
            throw ApiException.forbidden("NOT_CREDIT", "Only credit can ask for a co-applicant or guarantor");
        if (!editable(a)) throw ApiException.conflict("NOT_EDITABLE", "Too late to ask for a party: the application is " + a.getAppState().toLowerCase());
        String role = r.role() == null ? "" : r.role().trim().toUpperCase();
        if (!PartyRules.ROLES.contains(role)) throw ApiException.unprocessable("BAD_ROLE", "Role must be co-applicant or guarantor");
        if ("NOT_ALLOWED".equals(rules(a).slot(role).mode())) throw ApiException.unprocessable("PARTY_RULE", "This product does not allow a " + PartyRules.label(role));
        if (r.reason() == null || r.reason().trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Say why (at least 15 characters): sales passes it on to the borrower");
        PartyRequirement q = new PartyRequirement();
        q.setApplicationId(appId);
        q.setRole(role);
        q.setReason(trim(r.reason().trim(), 400));
        q.setRequestedBy(by.username());
        q = requirements.save(q);
        audit.record(by, "party.requested", "APPLICATION", a.getAppNo(), appId, PartyRules.label(role) + " · " + q.getReason());
        return q;
    }

    @Transactional
    public PartyRequirement waive(Long requirementId, String note, CurrentUser by) {
        PartyRequirement q = requirements.findById(requirementId).orElseThrow(() -> ApiException.notFound("Request"));
        if (!Set.of(Role.CREDIT_MANAGER, Role.CRO).contains(by.role())) throw ApiException.forbidden("NOT_AUTHORISED", "A credit manager or the CRO waives a request for a party");
        if (!"OPEN".equals(q.getStatus())) throw ApiException.conflict("NOT_OPEN", "This request is " + q.getStatus().toLowerCase());
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 15 characters");
        q.setStatus("WAIVED");
        q.setClosedBy(by.username());
        q.setClosedAt(Instant.now());
        q.setCloseNote(trim(note.trim(), 300));
        LoanApplication a = apps.get(q.getApplicationId());
        audit.record(by, "party.request_waived", "APPLICATION", a.getAppNo(), a.getId(), PartyRules.label(q.getRole()) + " · " + note.trim());
        return requirements.save(q);
    }

    // ---------------------------------------------------------------- signatures

    String signTerms(ApplicationParty p, LoanApplication a) {
        return PartyRules.GUARANTOR.equals(p.getRole())
                ? "I, " + p.getName() + ", guarantee repayment of loan " + a.getAppNo() + " and agree to pay any amount the borrower does not pay when due, under the guarantee deed shown to me."
                : "I, " + p.getName() + ", accept the Key Fact Statement and loan agreement for " + a.getAppNo() + " as co-applicant, and am jointly and severally liable for the whole loan.";
    }

    @Transactional(noRollbackFor = ApiException.class)
    public ApplicationParty signInBranch(Long partyId, String otp, CurrentUser by) {
        ApplicationParty p = get(partyId);
        checkOtp(p, otp);
        return sign(p, "BRANCH_OTP", by.username());
    }

    @Transactional
    public ApplicationParty signByLink(String token, String session) {
        ApplicationParty p = bySession(token, session);
        return sign(p, "INVITE_LINK", "party:" + p.getPartyNo());
    }

    private ApplicationParty sign(ApplicationParty p, String channel, String actor) {
        LoanApplication a = apps.get(p.getApplicationId());
        if (!AFTER_SANCTION.contains(a.getSanctionState())) throw ApiException.conflict("NOT_SANCTIONED", "Parties sign after sanction");
        if (!p.active()) throw ApiException.conflict("NOT_ACTIVE", "This party is not active");
        if (p.getConsentAt() == null || !"VERIFIED".equals(p.getKycState())) throw ApiException.conflict("NOT_VERIFIED", p.getName() + " must be consented and KYC-verified to sign");
        if (p.getSignedAt() != null) return p;
        p.setSignedAt(Instant.now());
        p.setSignedChannel(channel);
        p.setSignedDocument(PartyRules.GUARANTOR.equals(p.getRole()) ? "GUARANTEE_DEED" : "KFS_AND_AGREEMENT");
        parties.save(p);
        audit.record(actor, actor.startsWith("party:") ? "PARTY" : "STAFF", "party.signed", "APPLICATION", a.getAppNo(), a.getId(),
                p.getPartyNo() + " " + p.getName() + " signed " + p.getSignedDocument().toLowerCase().replace('_', ' ') + " by OTP (" + channel.toLowerCase().replace('_', ' ') + ")");
        return p;
    }

    // ---------------------------------------------------------------- gates and the engine

    public List<String> coOwners(LoanApplication a) {
        Set<String> out = new LinkedHashSet<>();
        for (var c : collaterals.findByApplicationIdOrderByIdAsc(a.getId())) {
            if ("RELEASED".equals(c.getStatus())) continue;
            out.addAll(PartyRules.splitOwners(c.getOwnerName()));
            collateralDocs.findByCollateralIdOrderByIdAsc(c.getId()).stream().filter(d -> "SALE_DEED".equals(d.getDocType()))
                    .reduce((x, y) -> y).ifPresent(d -> {
                        Map<String, Object> fields = fieldsOf(d.getExtractedJson());
                        out.addAll(PartyRules.splitOwners(str(fields.get("owner"))));
                        Object co = fields.get("coOwners");
                        if (co instanceof Collection<?> l) l.forEach(x -> out.addAll(PartyRules.splitOwners(String.valueOf(x))));
                        else out.addAll(PartyRules.splitOwners(str(co)));
                    });
        }
        // the same person written twice ("Asha Rao" and "Smt Asha Rao") counts once
        List<String> unique = new ArrayList<>();
        for (String n : out) if (unique.stream().noneMatch(u -> NameMatch.score(u, n) >= 0.8)) unique.add(n);
        return unique;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> fieldsOf(String json) {
        if (json == null) return Map.of();
        Map<String, Object> m = Json.read(json, Map.class);
        Object f = m.get("fields");
        return f instanceof Map<?, ?> fm ? (Map<String, Object>) fm : m;
    }

    List<PartyRules.Party> views(Long appId) {
        List<PartyRules.Party> out = new ArrayList<>();
        for (ApplicationParty p : list(appId)) {
            double bEmi = 0;
            if (p.getBureauJson() != null) {
                BureauReport b = Json.read(p.getBureauJson(), BureauReport.class);
                bEmi = b.totalEmi();
            }
            out.add(new PartyRules.Party(p.getId(), p.getRole(), p.getRelationship(), p.getName(), p.getPan(), p.getDob(), p.isIncomeConsidered(), p.isCoOwner(),
                    p.getStatus(), p.getConsentAt() != null, p.getKycState(), p.getBureauState(), p.getBureauScore(),
                    p.getMonthlyIncome() == null ? 0 : p.getMonthlyIncome().doubleValue(), p.getExistingEmi() == null ? 0 : p.getExistingEmi().doubleValue(), bEmi,
                    p.getSignedAt() != null));
        }
        return out;
    }

    public List<PartyRules.Issue> issues(LoanApplication a) {
        PartyRules r = rules(a);
        Map<String, Object> facts = workflow == null ? com.rhythm.los.flow.WorkflowDefinitions.basicFacts(a, false) : workflow.facts(a);
        List<PartyRules.Requirement> open = requirements.findByApplicationIdOrderByIdAsc(a.getId()).stream().filter(q -> "OPEN".equals(q.getStatus()))
                .map(q -> new PartyRules.Requirement(q.getId(), q.getRole(), q.getReason())).toList();
        List<PartyRules.Issue> out = new ArrayList<>(r.check(facts, views(a.getId()), coOwners(a), a.getApplicantName(), open,
                AFTER_SANCTION.contains(a.getSanctionState()), a.getTenureMonths(), LocalDate.now()));
        // video KYC for co-applicants and guarantors, as the product sets it (every party, or to clear a KYC review)
        if (vkycSessions != null) {
            com.rhythm.los.vkyc.VkycPolicy vp = vkycPolicy(a);
            List<com.rhythm.los.vkyc.VkycSession> calls = vkycSessions.findByApplicationIdOrderByIdDesc(a.getId());
            for (ApplicationParty p : active(a.getId())) {
                if (p.getConsentAt() == null || !vp.partyNeedsCall(p.getKycState())) continue;
                boolean approved = calls.stream().anyMatch(c -> p.getId().equals(c.getPartyId()) && "APPROVED".equals(c.getStatus()));
                if (!approved && "ALL".equals(vp.parties()))
                    out.add(new PartyRules.Issue("PARTY_VKYC", "DECISION", p.getName() + " (" + PartyRules.label(p.getRole()) + "): video KYC not done (required for this product)", p.getRole()));
            }
        }
        // a party who joined after the decision is not in it: the decision must be run again before sanction
        decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).ifPresent(d -> {
            if (AFTER_SANCTION.contains(a.getSanctionState())) return;
            for (ApplicationParty p : active(a.getId()))
                if (joinedAfter(p, d.getCreatedAt()))
                    out.add(new PartyRules.Issue("RERUN_DECISION", "SANCTION", p.getName() + " joined after the decision: run the decision engine again", p.getRole()));
        });
        return out;
    }

    private static boolean joinedAfter(ApplicationParty p, Instant decidedAt) {
        return p.getConsentAt() != null && p.getConsentAt().isAfter(decidedAt) || p.getAddedAt().isAfter(decidedAt);
    }

    /** Plain-words blockers for a gate: SUBMIT, DECISION, SANCTION or DISBURSE. */
    @Override
    public List<String> blockers(LoanApplication a, String gate) {
        return PartyRules.blocking(issues(a), gate).stream().map(PartyRules.Issue::message).distinct().toList();
    }

    @Override
    public List<String> beforeSubmit(LoanApplication a) { return blockers(a, "SUBMIT"); }

    /** Joint income and the party policy, for the engine; null when there are no parties. */
    @Override
    public com.rhythm.los.decision.engine.EngineInput.Joint joint(LoanApplication a) {
        PartyRules.Joint j = jointView(a);
        return j == null ? null : new com.rhythm.los.decision.engine.EngineInput.Joint(j.coApplicants(), j.guarantors(), j.earnersCounted(),
                j.clubbedIncome(), j.clubbedEmi(), j.policyFails());
    }

    public PartyRules.Joint jointView(LoanApplication a) {
        List<PartyRules.Party> v = views(a.getId());
        if (v.stream().noneMatch(x -> !"REMOVED".equals(x.status()))) return null;
        PartyRules.Joint j = rules(a).joint(v, a.getTenureMonths(), LocalDate.now());
        List<String> fails = new ArrayList<>(j.policyFails());
        for (ApplicationParty p : active(a.getId()))
            if (PartyRules.GUARANTOR.equals(p.getRole())) {
                long other = parties.countOpenGuaranteesByPan(p.getPan(), a.getId());
                if (other >= MAX_OPEN_GUARANTEES) fails.add(p.getName() + " (guarantor): already guarantees " + other + " other open loans");
            }
        return new PartyRules.Joint(j.coApplicants(), j.guarantors(), j.earnersCounted(), j.clubbedIncome(), j.clubbedEmi(), fails);
    }

    /** Parties for the credit memo and sanction letter. */
    public List<Map<String, Object>> summary(Long appId) {
        return active(appId).stream().map(p -> Map.<String, Object>of("partyNo", p.getPartyNo(), "role", p.getRole(), "name", p.getName(),
                "relationship", p.getRelationship(), "kycState", p.getKycState(), "bureauScore", p.getBureauScore() == null ? "—" : p.getBureauScore(),
                "incomeConsidered", p.isIncomeConsidered(), "signed", p.getSignedAt() != null)).toList();
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> check(String name, double score, String expected, String found) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", name);
        m.put("score", Math.round(score * 100) / 100.0);
        m.put("expected", expected);
        m.put("found", found);
        return m;
    }

    static double dobMatch(LocalDate dob, String read) {
        if (read == null || read.isBlank()) return 0.5;
        for (java.time.format.DateTimeFormatter f : List.of(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE, java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy")))
            try { return LocalDate.parse(read.trim(), f).equals(dob) ? 1 : 0; } catch (Exception ignored) { /* next format */ }
        return read.trim().endsWith(String.valueOf(dob.getYear())) ? 0.7 : 0.5;
    }

    private static String join(String a, String b) { return a == null ? b : b == null ? a : a + " " + b; }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String trim(String s, int n) { return s == null ? null : s.length() > n ? s.substring(0, n - 3) + "..." : s; }
    private static String money(BigDecimal b) { return b == null ? "—" : com.rhythm.los.common.Fmt.rupees(b.doubleValue()); }

    static String token() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha(String s) { return sha(s.getBytes(StandardCharsets.UTF_8)); }

    static String sha(byte[] bytes) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
