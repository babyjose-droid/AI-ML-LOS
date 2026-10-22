package com.rhythm.los.sanction;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionService;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.product.Product;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class SanctionService {
    private final ApplicationService apps;
    private final DecisionService decisions;
    private final SanctionRecordRepository records;
    private final AuditService audit;
    private final String mockOtp;
    private final com.rhythm.los.document.DocumentService documents;
    private final com.rhythm.los.flow.WorkflowService workflow;

    public SanctionService(ApplicationService apps, DecisionService decisions, SanctionRecordRepository records,
                           AuditService audit, @Value("${rhythm.kfs.otp}") String mockOtp,
                           com.rhythm.los.document.DocumentService documents, com.rhythm.los.flow.WorkflowService workflow) {
        this.workflow = workflow;
        this.documents = documents;
        this.apps = apps;
        this.decisions = decisions;
        this.records = records;
        this.audit = audit;
        this.mockOtp = mockOtp;
    }

    private String pendingLevel(LoanApplication a) {
        String s = a.getSanctionState();
        if (!s.startsWith("PENDING_")) throw ApiException.conflict("NOT_PENDING", "Sanction is " + s);
        return s.substring("PENDING_".length());
    }

    private void checkAuthority(String level, CurrentUser by) {
        int mine = by.role().sanctionLevel();
        if (mine < Delegation.rank(level)) {
            throw ApiException.forbidden("ABOVE_AUTHORITY", "This case needs " + level + " authority; your role " + by.role() + " has L" + mine);
        }
    }

    @Transactional
    public SanctionRecord approve(Long appId, BigDecimal amount, String note, CurrentUser by) {
        return approve(appId, amount, note, null, by);
    }

    /** Approve, optionally with extra conditions the approver adds (FR-UW-06 "approve with conditions"). */
    @Transactional
    public SanctionRecord approve(Long appId, BigDecimal amount, String note, java.util.List<String> extraConditions, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        String level = pendingLevel(a);
        checkAuthority(level, by);
        if (!"CLEAR".equals(a.getFraudState())) throw ApiException.conflict("FRAUD_NOT_CLEARED", "Fraud review must be cleared before sanction");
        if ("REQUIRED".equals(a.getFieldState()))
            throw ApiException.conflict("FIELD_VISIT_PENDING", "The decision is provisional: record the field visit before sanction");
        workflow.requireNothingPendingBefore(a, "sanction");
        DecisionRecord d = decisions.latest(appId).orElseThrow(() -> ApiException.conflict("NO_DECISION", "Run the decision engine first"));
        Product p = apps.product(a);
        com.rhythm.los.collateral.CollateralService.SanctionCheck col = collateral == null ? null : collateral.sanctionCheck(a);
        if (col != null && col.blocked()) throw ApiException.conflict("COLLATERAL_NOT_CLEARED", col.reason());
        List<String> partyIssues = partyBlockers(a, "SANCTION");
        if (!partyIssues.isEmpty()) throw ApiException.conflict("PARTIES_NOT_READY", "Co-applicants and guarantors: " + String.join("; ", partyIssues));

        BigDecimal amt = amount == null ? d.getRecommendedAmount() : amount;
        boolean override = "REFER".equals(d.getDecision()) || amt.compareTo(d.getRecommendedAmount()) > 0;
        if (override && (note == null || note.trim().length() < 10)) {
            throw ApiException.unprocessable("REASON_REQUIRED", "Approving a referred case or above the recommended amount needs a reason of at least 10 characters");
        }
        if (amt.compareTo(p.getMinAmount()) < 0 || amt.compareTo(p.getMaxAmount()) > 0 || amt.compareTo(a.getLoanAmount()) > 0) {
            throw ApiException.unprocessable("AMOUNT_OUT_OF_RANGE", "Sanction amount must be within product limits and not above the amount applied for");
        }
        // asset finance: the asset rules must pass, and the amount stays within the asset's maximum loan
        if (assets != null) assets.requireReadyForSanction(a, amt);
        if (trade != null) trade.requireReadyForSanction(a);
        if (plan != null) plan.requireReadyForSanction(a, amt);
        if (pledge != null) pledge.requireReadyForSanction(a, amt);
        // COL-BR-13: above the collateral's maximum exposure (LTV cap) only L3 may sanction, with a reason
        boolean ltvException = col != null && col.maxExposure() != null && amt.doubleValue() > col.maxExposure() + 0.5;
        if (ltvException) {
            if (note == null || note.trim().length() < 20)
                throw ApiException.unprocessable("LTV_EXCEPTION_REASON", "Sanction above the collateral's maximum exposure ₹" + Math.round(col.maxExposure()) + " needs L3 and a reason of at least 20 characters");
            level = "L3";
            checkAuthority(level, by);
        }
        // the amount actually sanctioned decides the authority needed, not the recommended amount the case was routed on
        String needed = Delegation.levelForAmount(amt.doubleValue());
        if (Delegation.rank(needed) > Delegation.rank(level)) {
            level = needed;
            checkAuthority(level, by);
        }

        Kfs kfs = KfsCalculator.build(a.getAppNo(), a.getApplicantName(), p.getName(), amt.doubleValue(),
                d.getRate().doubleValue(), a.getTenureMonths(), p.charges()).withParties(kfsParties(appId));

        SanctionRecord s = new SanctionRecord();
        s.setApplicationId(appId);
        s.setDecisionId(d.getId());
        s.setAction("APPROVED");
        s.setLevel(level);
        s.setAmount(amt);
        s.setRate(d.getRate());
        s.setTenureMonths(a.getTenureMonths());
        s.setOverrideFlag(override);
        s.setNote(note);
        s.setKfsJson(Json.write(kfs));
        s.setActor(by.username());
        java.util.List<String> conds = new java.util.ArrayList<>();
        if (extraConditions != null) extraConditions.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).forEach(conds::add);
        if (col != null) col.conditions().stream().filter(x -> !conds.contains(x)).forEach(conds::add);
        if (!conds.isEmpty()) s.setConditionsJson(Json.write(conds));
        s = records.save(s);

        apps.transition(a, Domain.SANCTION, "SANCTIONED", "sanction.approved", by,
                level + " · ₹" + amt.toPlainString() + (override ? " · OVERRIDE: " + note : ""));
        apps.transition(a, Domain.APP, "SANCTIONED", "application.sanctioned", by, null);
        if (override) audit.record(by, "sanction.override", "APPLICATION", a.getAppNo(), appId, note);
        if (ltvException) audit.record(by, "collateral.ltv_exception", "APPLICATION", a.getAppNo(), appId, "₹" + amt.toPlainString() + " above maximum exposure ₹" + Math.round(col.maxExposure()) + " · " + note);
        return s;
    }

    private org.springframework.beans.factory.ObjectProvider<com.rhythm.los.decision.DecisionService.PartyGate> partyGates;

    @org.springframework.beans.factory.annotation.Autowired
    void setPartyGates(org.springframework.beans.factory.ObjectProvider<com.rhythm.los.decision.DecisionService.PartyGate> p) { this.partyGates = p; }

    /** What co-applicants and guarantors still need before the gate (SANCTION or DISBURSE). */
    public List<String> partyBlockers(LoanApplication a, String gate) {
        var g = partyGates == null ? null : partyGates.getIfAvailable();
        return g == null ? List.of() : g.blockers(a, gate);
    }

    private com.rhythm.los.party.ApplicationPartyRepository partyRepo;

    @org.springframework.beans.factory.annotation.Autowired
    void setPartyRepo(com.rhythm.los.party.ApplicationPartyRepository r) { this.partyRepo = r; }

    /** Co-applicants and guarantors named on the KFS and the sanction letter. */
    List<Kfs.Party> kfsParties(Long appId) {
        if (partyRepo == null) return List.of();
        return partyRepo.findByApplicationIdOrderByIdAsc(appId).stream().filter(com.rhythm.los.party.ApplicationParty::active)
                .map(x -> new Kfs.Party(x.getRole(), x.entity() ? x.getName() + " (" + x.getPartyType().toLowerCase() + ", signing through " + x.getSignatoryName() + ")" : x.getName(),
                        x.getRelationship() == null ? "" : x.getRelationship().toLowerCase().replace('_', ' '))).toList();
    }

    private com.rhythm.los.collateral.CollateralService collateral;
    private com.rhythm.los.asset.AssetService assets;
    private com.rhythm.los.trade.TradeService trade;
    private com.rhythm.los.plan.PlanService plan;
    private com.rhythm.los.pledge.PledgeService pledge;

    @org.springframework.beans.factory.annotation.Autowired
    void setPledge(@org.springframework.context.annotation.Lazy com.rhythm.los.pledge.PledgeService p) { this.pledge = p; }

    @org.springframework.beans.factory.annotation.Autowired
    void setPlan(@org.springframework.context.annotation.Lazy com.rhythm.los.plan.PlanService p) { this.plan = p; }

    @org.springframework.beans.factory.annotation.Autowired
    void setTrade(@org.springframework.context.annotation.Lazy com.rhythm.los.trade.TradeService t) { this.trade = t; }

    @org.springframework.beans.factory.annotation.Autowired
    void setAssets(@org.springframework.context.annotation.Lazy com.rhythm.los.asset.AssetService a) { this.assets = a; }

    @org.springframework.beans.factory.annotation.Autowired
    void setCollateral(com.rhythm.los.collateral.CollateralService c) { this.collateral = c; }

    /**
     * Straight-through sanction for small, clean cases, when the product allows it: engine APPROVE (not conditional),
     * amount as applied and within the product's auto-sanction limit, KYC verified, fraud clear, no edit signal on any
     * document, no field visit pending, and routed to the lowest level. Returns empty when the case must go to a person.
     */
    @Transactional
    public java.util.Optional<SanctionRecord> autoSanction(Long appId) {
        LoanApplication a = apps.get(appId);
        Product p = apps.product(a);
        if (p.getAutoSanctionLimit() == null || !"PENDING_L1".equals(a.getSanctionState())) return java.util.Optional.empty();
        // secured products never sanction straight through: collateral needs its own clearance
        if (!"NOT_REQUIRED".equals(a.getCollateralState())) return java.util.Optional.empty();
        // co-applicants and guarantors are looked at by a person
        if (partyGates != null && partyGates.getIfAvailable() != null && partyGates.getIfAvailable().joint(a) != null) return java.util.Optional.empty();
        DecisionRecord d = decisions.latest(appId).orElse(null);
        if (d == null || !"APPROVE".equals(d.getDecision())) return java.util.Optional.empty();
        boolean clean = "VERIFIED".equals(a.getKycState()) && "CLEAR".equals(a.getFraudState()) && !documents.anyTampered(appId)
                && !"REQUIRED".equals(a.getFieldState())
                && d.getRecommendedAmount().compareTo(a.getLoanAmount()) == 0
                && a.getLoanAmount().compareTo(p.getAutoSanctionLimit()) <= 0;
        if (!clean || !workflow.pendingBefore(a, "sanction").isEmpty()) return java.util.Optional.empty();
        if (pledge != null && pledge.isPledge(a)) {
            try { pledge.requireReadyForSanction(a, a.getLoanAmount()); } catch (com.rhythm.los.common.ApiException e) { return java.util.Optional.empty(); }
        }
        if (plan != null && plan.isPlan(a)) {
            try { plan.requireReadyForSanction(a, a.getLoanAmount()); } catch (com.rhythm.los.common.ApiException e) { return java.util.Optional.empty(); }
        }
        if (trade != null && trade.isTrade(a)) {
            try { trade.requireReadyForSanction(a); } catch (com.rhythm.los.common.ApiException e) { return java.util.Optional.empty(); }
        }
        if (assets != null && assets.isAssetProduct(a)) {
            try { assets.requireReadyForSanction(a, a.getLoanAmount()); } catch (com.rhythm.los.common.ApiException e) { return java.util.Optional.empty(); }
        }
        CurrentUser sys = CurrentUser.SYSTEM;
        Kfs kfs = KfsCalculator.build(a.getAppNo(), a.getApplicantName(), p.getName(), a.getLoanAmount().doubleValue(),
                d.getRate().doubleValue(), a.getTenureMonths(), p.charges());
        SanctionRecord s = new SanctionRecord();
        s.setApplicationId(appId);
        s.setDecisionId(d.getId());
        s.setAction("APPROVED");
        s.setLevel("AUTO");
        s.setAmount(a.getLoanAmount());
        s.setRate(d.getRate());
        s.setTenureMonths(a.getTenureMonths());
        s.setOverrideFlag(false);
        s.setNote("Auto-sanctioned: engine approved, ₹" + a.getLoanAmount().toPlainString() + " within the product limit of ₹" + p.getAutoSanctionLimit().toPlainString());
        s.setKfsJson(Json.write(kfs));
        s.setActor(sys.username());
        s = records.save(s);
        apps.transition(a, Domain.SANCTION, "SANCTIONED", "sanction.auto_approved", sys, s.getNote());
        apps.transition(a, Domain.APP, "SANCTIONED", "application.sanctioned", sys, null);
        audit.record(sys, "sanction.auto", "APPLICATION", a.getAppNo(), appId, s.getNote());
        return java.util.Optional.of(s);
    }

    @Transactional
    public SanctionRecord decline(Long appId, String note, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        String level = pendingLevel(a);
        checkAuthority(level, by);
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        SanctionRecord s = record(appId, "DECLINED", level, note, by);
        apps.transition(a, Domain.SANCTION, "DECLINED", "sanction.declined", by, note);
        apps.reject(a, "Declined at " + level + ": " + note, by);
        return s;
    }

    /** Sends the case back to credit for re-assessment (FR-SAN-02): sanction is reset and the decision is re-opened. */
    @Transactional
    public SanctionRecord sendBack(Long appId, String note, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        String level = pendingLevel(a);
        checkAuthority(level, by);
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        SanctionRecord s = record(appId, "SENT_BACK", level, note.trim(), by);
        apps.transition(a, Domain.SANCTION, "NOT_STARTED", "sanction.sent_back", by, level + ": " + note.trim());
        apps.transition(a, Domain.DECISION, "PENDING", "decision.reopened", by, "Sent back by " + by.username() + " for re-assessment");
        audit.record(by, "sanction.sent_back", "APPLICATION", a.getAppNo(), appId, level + ": " + note.trim());
        return s;
    }

    @Transactional
    public SanctionRecord escalate(Long appId, String note, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        String level = pendingLevel(a);
        checkAuthority(level, by);
        if ("L3".equals(level)) throw ApiException.conflict("TOP_LEVEL", "L3 is the highest authority");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        String next = "L1".equals(level) ? "L2" : "L3";
        SanctionRecord s = record(appId, "ESCALATED", level, note, by);
        apps.transition(a, Domain.SANCTION, "PENDING_" + next, "sanction.escalated", by, level + " -> " + next + ": " + note);
        return s;
    }

    /** True while a case sent back from sanction waits for credit to re-assess it (no decision run since). */
    public boolean awaitingReassessment(LoanApplication a) {
        if (!"PENDING".equals(a.getDecisionState())) return false;
        return records.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "SENT_BACK")
                .map(s -> decisions.latestCreatedAt(a.getId()).map(d -> d.isBefore(s.getCreatedAt())).orElse(true))
                .orElse(false);
    }

    /**
     * Sanction letter (FR-SAN-03): the approved terms, the conditions from the engine and the approver, the KFS and the
     * approver's details, for the borrower's language and English side by side.
     */
    public java.util.Map<String, Object> letter(Long appId, String lang) {
        LoanApplication a = apps.get(appId);
        SanctionRecord r = records.findFirstByApplicationIdAndActionOrderByIdDesc(appId, "APPROVED")
                .orElseThrow(() -> ApiException.conflict("NOT_SANCTIONED", "There is no sanction yet"));
        java.util.List<String> conditions = new java.util.ArrayList<>();
        decisions.latest(appId).ifPresent(d -> { if (d.getConditionsJson() != null) conditions.addAll(java.util.List.of(Json.read(d.getConditionsJson(), String[].class))); });
        if (r.getConditionsJson() != null) conditions.addAll(java.util.List.of(Json.read(r.getConditionsJson(), String[].class)));
        conditions.removeIf(c -> c.startsWith("Provisional:"));
        String language = lang != null && lang.matches("en|hi|mr|ta|ml|kn|te|bn|gu") ? lang : a.getPreferredLanguage() == null ? "en" : a.getPreferredLanguage();
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("language", language);
        m.put("lender", lenderName);
        m.put("applicationNo", a.getAppNo());
        m.put("borrower", a.getApplicantName());
        m.put("address", java.util.stream.Stream.of(a.getAddress(), a.getCity(), a.getPincode()).filter(x -> x != null && !x.isBlank()).collect(java.util.stream.Collectors.joining(", ")));
        m.put("product", apps.product(a).getName());
        m.put("sanctionedOn", r.getCreatedAt());
        m.put("approver", r.getActor());
        m.put("level", r.getLevel());
        m.put("conditions", conditions);
        Kfs k = Json.read(r.getKfsJson(), Kfs.class);
        // sanctions made before parties were on the KFS: name them from the application
        if (k.parties() == null || k.parties().isEmpty()) k = k.withParties(kfsParties(appId));
        m.put("kfs", k);
        m.put("parties", k.parties());
        m.put("acceptedAt", r.getKfsAcceptedAt());
        m.put("acceptedLanguage", r.getKfsLanguage());
        m.put("validDays", 30);
        // wording the lender changed (Admin → Templates), for the letter's language and English
        if (templates != null) {
            m.put("texts", templates.letterTexts(language));
            m.put("textsEn", templates.letterTexts("en"));
        }
        return m;
    }

    @Value("${rhythm.lender.name:Rhythm Demo Finance Ltd (NBFC)}") String lenderName = "Rhythm Demo Finance Ltd (NBFC)";

    private com.rhythm.los.template.TemplateService templates;

    @org.springframework.beans.factory.annotation.Autowired
    void setTemplates(com.rhythm.los.template.TemplateService t) { this.templates = t; }

    public Kfs kfs(Long appId) {
        return records.findFirstByApplicationIdAndActionOrderByIdDesc(appId, "APPROVED")
                .map(r -> Json.read(r.getKfsJson(), Kfs.class))
                .map(k -> k.parties() == null || k.parties().isEmpty() ? k.withParties(kfsParties(appId)) : k)
                .orElseThrow(() -> ApiException.notFound("Key Fact Statement"));
    }

    @Transactional
    public LoanApplication acceptKfs(Long appId, String otp, CurrentUser by) { return acceptKfs(appId, otp, null, by); }

    /** The borrower accepts by OTP; the language the KFS was shown in is stored with the acceptance (FR-SAN-04). */
    @Transactional
    public LoanApplication acceptKfs(Long appId, String otp, String language, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!"SANCTIONED".equals(a.getSanctionState())) throw ApiException.conflict("NOT_SANCTIONED", "Sanction is " + a.getSanctionState());
        if (otp == null || !otp.equals(mockOtp)) throw ApiException.unprocessable("BAD_OTP", "The OTP is not correct");
        SanctionRecord r = records.findFirstByApplicationIdAndActionOrderByIdDesc(appId, "APPROVED").orElseThrow();
        r.setKfsAcceptedAt(Instant.now());
        String lang = language == null || !language.matches("en|hi|mr|ta|ml|kn|te|bn|gu") ? a.getPreferredLanguage() : language;
        r.setKfsLanguage(lang == null ? "en" : lang);
        records.save(r);
        apps.transition(a, Domain.SANCTION, "KFS_ACCEPTED", "kfs.accepted", by, "Borrower accepted KFS with OTP");
        apps.transition(a, Domain.DISB, "READY", "disbursement.ready", by, null);
        return a;
    }

    private SanctionRecord record(Long appId, String action, String level, String note, CurrentUser by) {
        SanctionRecord s = new SanctionRecord();
        s.setApplicationId(appId);
        s.setAction(action);
        s.setLevel(level);
        s.setNote(note);
        s.setActor(by.username());
        decisions.latest(appId).ifPresent(d -> s.setDecisionId(d.getId()));
        return records.save(s);
    }
}
