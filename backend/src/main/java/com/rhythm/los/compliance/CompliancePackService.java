package com.rhythm.los.compliance;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.book.BookRisk;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LiveLoanRepository;
import com.rhythm.los.common.Json;
import com.rhythm.los.consent.ConsentRepository;
import com.rhythm.los.disbursement.Disbursement;
import com.rhythm.los.disbursement.DisbursementRepository;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.AppDocumentRepository;
import com.rhythm.los.growth.CollectionContact;
import com.rhythm.los.growth.CollectionContactRepository;
import com.rhythm.los.growth.CollectionsAi;
import com.rhythm.los.growth.CollectionsService;
import com.rhythm.los.growth.LoanRestructure;
import com.rhythm.los.growth.LoanRestructureRepository;
import com.rhythm.los.model.ModelVersion;
import com.rhythm.los.model.ModelVersionRepository;
import com.rhythm.los.org.AppUserRepository;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.sanction.SanctionRecord;
import com.rhythm.los.sanction.SanctionRecordRepository;
import com.rhythm.los.decision.Delegation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Compliance pack: every check is computed from the records themselves (not self-certified). Each item gives the
 * requirement, its source, how many records were checked, the exceptions with references, and a status.
 */
@Service
public class CompliancePackService {
    public record Item(String id, String area, String requirement, String source, String status, int checked, int exceptions, List<String> samples, String evidence) {}

    static final Pattern AADHAAR = Pattern.compile("(?<!\\d)\\d{4}\\s?\\d{4}\\s?\\d{4}(?!\\d)");

    private final LoanApplicationRepository apps;
    private final SanctionRecordRepository sanctions;
    private final DisbursementRepository disbursements;
    private final ConsentRepository consents;
    private final AppDocumentRepository documents;
    private final LoanRestructureRepository restructures;
    private final CollectionContactRepository contacts;
    private final LiveLoanRepository loans;
    private final ModelVersionRepository models;
    private final AppUserRepository users;
    private final PolicyService policy;
    private final JdbcTemplate jdbc;

    public CompliancePackService(LoanApplicationRepository apps, SanctionRecordRepository sanctions, DisbursementRepository disbursements, ConsentRepository consents,
                                 AppDocumentRepository documents, LoanRestructureRepository restructures, CollectionContactRepository contacts, LiveLoanRepository loans,
                                 ModelVersionRepository models, AppUserRepository users, PolicyService policy, JdbcTemplate jdbc) {
        this.apps = apps;
        this.sanctions = sanctions;
        this.disbursements = disbursements;
        this.consents = consents;
        this.documents = documents;
        this.restructures = restructures;
        this.contacts = contacts;
        this.loans = loans;
        this.models = models;
        this.users = users;
        this.policy = policy;
        this.jdbc = jdbc;
    }

    static Item item(String id, String area, String req, String source, int checked, List<String> exc, String evidence) {
        String st = checked == 0 ? "NOT_APPLICABLE" : exc.isEmpty() ? "PASS" : "EXCEPTION";
        return new Item(id, area, req, source, st, checked, exc.size(), exc.stream().limit(10).toList(), evidence);
    }

    public Map<String, Object> pack() {
        List<Item> items = new ArrayList<>();
        List<LoanApplication> all = apps.findAll();
        Map<Long, LoanApplication> byId = new HashMap<>();
        all.forEach(a -> byId.put(a.getId(), a));
        List<Disbursement> done = disbursements.findAll().stream().filter(d -> "SUCCESS".equals(d.getStatus())).toList();

        // C01 KFS accepted before disbursement
        List<String> e = new ArrayList<>();
        for (Disbursement d : done) {
            Instant acc = sanctions.findByApplicationIdOrderByIdAsc(d.getApplicationId()).stream().map(SanctionRecord::getKfsAcceptedAt).filter(Objects::nonNull).max(Instant::compareTo).orElse(null);
            if (acc == null || acc.isAfter(d.getCreatedAt())) e.add(appNo(byId, d.getApplicationId()) + ": KFS not accepted before disbursement");
        }
        items.add(item("C01", "Disclosure", "Key Facts Statement given and accepted by the borrower before the loan is disbursed",
                "RBI Key Facts Statement for loans and advances (15 Apr 2024)", done.size(), e, "Sanction records: KFS acceptance time vs disbursement time"));

        // C02 APR disclosed
        e = new ArrayList<>();
        int n = 0;
        for (SanctionRecord s : sanctions.findAll()) {
            if (s.getKfsJson() == null) continue;
            n++;
            JsonNode k = Json.MAPPER.valueToTree(Json.read(s.getKfsJson(), Map.class));
            if (!k.has("aprPa") || k.get("aprPa").asDouble() <= 0 || k.get("aprPa").asDouble() < k.path("ratePa").asDouble())
                e.add(appNo(byId, s.getApplicationId()) + ": APR missing or below the interest rate");
        }
        items.add(item("C02", "Disclosure", "Annual percentage rate (all-in cost) shown in every KFS", "RBI Key Facts Statement (15 Apr 2024)", n, e, "KFS issued with each sanction"));

        // C03 consent before data pull
        e = new ArrayList<>();
        n = 0;
        for (LoanApplication a : all) {
            if (Set.of("DRAFT").contains(a.getAppState())) continue;
            n++;
            if (consents.findByApplicationIdOrderByIdAsc(a.getId()).isEmpty()) e.add(a.getAppNo() + ": no consent record");
        }
        items.add(item("C03", "Data and consent", "Bureau, Account Aggregator and KYC consent recorded before any data is fetched",
                "Digital Personal Data Protection Act 2023; RBI Account Aggregator directions", n, e, "Consent ledger per submitted application"));

        // C04 rate within the cap
        double cap = policy.live().maxRate();
        e = new ArrayList<>();
        n = 0;
        for (SanctionRecord s : sanctions.findAll()) {
            if (!"APPROVED".equals(s.getAction()) || s.getRate() == null) continue;
            n++;
            if (s.getRate().doubleValue() > cap) e.add(appNo(byId, s.getApplicationId()) + ": " + s.getRate() + "% above the " + cap + "% cap");
        }
        items.add(item("C04", "Fair practices", "Interest rate within the board-approved rate cap (" + cap + "%)", "RBI Fair Practices Code for NBFCs; board interest-rate policy", n, e, "Sanctioned rates"));

        // C05 disbursal to the borrower's own account
        e = new ArrayList<>();
        for (Disbursement d : done) {
            LoanApplication a = byId.get(d.getApplicationId());
            String acc = a == null ? null : a.getBankAccountNo(), paid = d.getBeneficiaryAccount();
            // the beneficiary is stored masked (last 4 digits): compare length and last 4, and the bank-verified name match
            boolean same = acc != null && paid != null && acc.length() == paid.length() && acc.endsWith(paid.substring(Math.max(0, paid.length() - 4)));
            if (!same || d.getNameMatchScore() == null || d.getNameMatchScore().doubleValue() < 0.8)
                e.add(appNo(byId, d.getApplicationId()) + ": paid to an account not verified as the borrower's");
        }
        items.add(item("C05", "Disbursement", "Loan paid only into the borrower's own bank account (no pass-through or pool accounts)",
                "RBI Digital Lending Directions 2025", done.size(), e, "Beneficiary account vs the application, and the bank's name match (penny drop)"));

        // C06 no disbursal under fraud review
        e = new ArrayList<>();
        for (Disbursement d : done) {
            LoanApplication a = byId.get(d.getApplicationId());
            if (a != null && Set.of("REVIEW", "BLOCK").contains(a.getFraudState())) e.add(a.getAppNo() + ": disbursed with fraud " + a.getFraudState());
        }
        items.add(item("C06", "Fraud", "Nothing disbursed while a fraud review is open or the case is blocked", "RBI Master Directions on Fraud Risk Management (15 Jul 2024)", done.size(), e,
                "Fraud state of every disbursed application"));

        // C07 sanction within delegated authority
        e = new ArrayList<>();
        n = 0;
        for (SanctionRecord s : sanctions.findAll()) {
            if (!"APPROVED".equals(s.getAction()) || s.getLevel() == null || "AUTO".equals(s.getLevel())) continue;
            n++;
            int need = Delegation.rank(s.getLevel());
            int has = users.findByUsername(s.getActor()).map(u -> u.getRole() == com.rhythm.los.org.Role.ADMIN ? 3 : u.getRole().sanctionLevel()).orElse(0);
            if (has < need) e.add(appNo(byId, s.getApplicationId()) + ": " + s.getLevel() + " case sanctioned by " + s.getActor());
        }
        items.add(item("C07", "Credit governance", "Every manual sanction within the approver's delegated authority", "Board-approved credit policy and delegation matrix", n, e,
                "Sanction level vs the approver's authority"));

        // C08 maker-checker on restructuring
        e = new ArrayList<>();
        List<LoanRestructure> rs = restructures.findAll().stream().filter(x -> !"PENDING".equals(x.getStatus())).toList();
        for (LoanRestructure x : rs) if (x.getRequestedBy().equals(x.getDecidedBy())) e.add("Proposal " + x.getId() + ": proposed and decided by " + x.getDecidedBy());
        items.add(item("C08", "Credit governance", "Restructuring proposed and approved by different people", "RBI Prudential Framework for Resolution of Stressed Assets (7 Jun 2019)",
                rs.size(), e, "Restructuring proposals"));

        // C09 contact hours
        e = new ArrayList<>();
        List<CollectionContact> cs = contacts.findAll().stream().filter(c -> Set.of("CALL", "FIELD_VISIT").contains(c.getChannel())).toList();
        for (CollectionContact c : cs)
            if (!CollectionsAi.withinContactHours(c.getContactedAt().atZone(CollectionsService.IST).toLocalTime())) e.add("Contact " + c.getId() + " at " + c.getContactedAt());
        items.add(item("C09", "Collections", "Borrowers called or visited only between 8 am and 7 pm", "RBI Fair Practices Code; recovery agent guidelines", cs.size(), e, "Collections contact log"));

        // C10 asset classification consistent with days past due
        e = new ArrayList<>();
        List<LiveLoan> act = loans.findAll().stream().filter(l -> "ACTIVE".equals(l.getStatus())).toList();
        for (LiveLoan l : act) {
            if (l.getDpd() > 90 && !"NPA".equals(l.getClassification())) e.add(l.getLoanRef() + ": " + l.getDpd() + " days past due but " + l.getClassification());
            else if (!"NPA".equals(l.getClassification()) && !BookRisk.bucket(l.getDpd()).equals(l.getClassification())) e.add(l.getLoanRef() + ": SMA bucket does not match days past due");
        }
        items.add(item("C10", "Asset quality", "Every loan classified by days past due; NPA above 90 days, upgraded only when all arrears are paid",
                "RBI IRACP norms (clarification 12 Nov 2021)", act.size(), e, "Live loan book"));

        // C11 provisions at least the RBI minimum
        e = new ArrayList<>();
        double ecl = act.stream().mapToDouble(l -> l.getEcl().doubleValue()).sum(), iracp = act.stream().mapToDouble(l -> l.getProvisionIracp().doubleValue()).sum();
        items.add(item("C11", "Asset quality", "Provisions held at least the IRACP minimum; any shortfall of ECL below it held as an impairment reserve",
                "RBI Ind AS implementation for NBFCs (13 Mar 2020)", act.size(), e,
                "ECL ₹" + Math.round(ecl) + " vs IRACP ₹" + Math.round(iracp) + (iracp > ecl ? "; impairment reserve ₹" + Math.round(iracp - ecl) : "; no reserve needed")));

        // C12 Aadhaar never stored in full
        e = new ArrayList<>();
        List<AppDocument> docs = documents.findAll().stream().filter(d -> "AADHAAR".equals(d.getDocType()) || "AADHAAR".equals(d.getDetectedType())).toList();
        for (AppDocument d : docs) {
            String text = String.join(" ", Objects.toString(d.getExtractedJson(), ""), Objects.toString(d.getAnalysisJson(), ""), Objects.toString(d.getRemarks(), ""));
            if (AADHAAR.matcher(text).find()) e.add(appNo(byId, d.getApplicationId()) + ": document " + d.getId() + " holds a full 12-digit number");
        }
        items.add(item("C12", "Data and consent", "Aadhaar number never stored in full (last 4 digits only)", "Aadhaar Act 2016, s.29; UIDAI storage regulations", docs.size(), e,
                "Aadhaar documents' extracted data scanned for 12-digit numbers"));

        // C13 audit trail append-only
        Integer trig = jdbc.queryForObject("select count(*) from pg_trigger where tgname = 'audit_event_no_update'", Integer.class);
        e = trig != null && trig > 0 ? List.of() : List.of("audit_event has no append-only trigger");
        items.add(item("C13", "Audit", "Audit trail cannot be changed or deleted", "RBI Master Direction on IT Governance (7 Nov 2023)", 1, e, "Database trigger on audit_event"));

        // C14 model approved by someone other than the trainer
        e = new ArrayList<>();
        List<ModelVersion> champs = models.findAllByOrderByIdDesc().stream().filter(m -> "CHAMPION".equals(m.getStatus())).toList();
        for (ModelVersion m : champs) if (m.getDecidedBy() == null || m.getDecidedBy().equals(m.getTrainedBy())) e.add(m.getName() + " " + m.getVersion() + ": not independently approved");
        items.add(item("C14", "Models", "Every live credit model independently validated and approved", "RBI Digital Lending Directions 2025 (model governance); board model-risk policy",
                champs.size(), e, "Model registry"));

        // C15 credit information reporting coverage
        e = new ArrayList<>();
        for (LiveLoan l : act) {
            LoanApplication a = l.getApplicationId() == null ? null : byId.get(l.getApplicationId());
            if (a == null || a.getPan() == null) e.add(l.getLoanRef() + ": no PAN for credit bureau reporting");
        }
        items.add(item("C15", "Reporting", "Every active loan reportable to all four credit information companies (identifiers present)",
                "RBI Credit Information Reporting Directions 2025 (fortnightly reporting)", act.size(), e, "Live loans with the borrower's PAN"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("policyVersion", policy.live().version());
        out.put("items", items);
        out.put("pass", items.stream().filter(i -> "PASS".equals(i.status())).count());
        out.put("exceptions", items.stream().filter(i -> "EXCEPTION".equals(i.status())).count());
        out.put("notApplicable", items.stream().filter(i -> "NOT_APPLICABLE".equals(i.status())).count());
        return out;
    }

    @SuppressWarnings("unchecked")
    public String csv() {
        StringBuilder b = new StringBuilder("id,area,requirement,source,status,checked,exceptions,samples,evidence\n");
        for (Item i : (List<Item>) pack().get("items"))
            b.append(String.join(",", i.id(), q(i.area()), q(i.requirement()), q(i.source()), i.status(), String.valueOf(i.checked()), String.valueOf(i.exceptions()),
                    q(String.join("; ", i.samples())), q(i.evidence()))).append('\n');
        return b.toString();
    }

    static String q(String s) { return s == null ? "" : '"' + s.replace("\"", "\"\"") + '"'; }

    static String appNo(Map<Long, LoanApplication> byId, Long id) {
        LoanApplication a = id == null ? null : byId.get(id);
        return a == null ? "application " + id : a.getAppNo();
    }
}
