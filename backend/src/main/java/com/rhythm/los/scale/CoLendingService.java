package com.rhythm.los.scale;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoanRepository;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.document.AppDocumentRepository;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.sanction.SanctionRecord;
import com.rhythm.los.sanction.SanctionRecordRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Co-lending matcher (FSD 17.3): every sanctioned or disbursed loan checked against each partner's rule set, the
 * eligible pool with the partner share and blended rate, reasons for non-eligibility, the partner loan file and the
 * partner's approval status.
 */
@Service
public class CoLendingService {
    public record Submit(Long partnerId, List<Long> applicationIds) {}
    public record Decide(boolean approve, String partnerRef, String note) {}

    private final CoLendingPartnerRepository partners;
    private final CoLendingAssignmentRepository assignments;
    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;
    private final SanctionRecordRepository sanctions;
    private final AppDocumentRepository documents;
    private final LiveLoanRepository loans;
    private final DataScope scope;
    private final AuditService audit;

    public CoLendingService(CoLendingPartnerRepository partners, CoLendingAssignmentRepository assignments, LoanApplicationRepository apps,
                            DecisionRecordRepository decisions, SanctionRecordRepository sanctions, AppDocumentRepository documents, LiveLoanRepository loans,
                            DataScope scope, AuditService audit) {
        this.partners = partners;
        this.assignments = assignments;
        this.apps = apps;
        this.decisions = decisions;
        this.sanctions = sanctions;
        this.documents = documents;
        this.loans = loans;
        this.scope = scope;
        this.audit = audit;
    }

    static CoLendingRules.Partner rules(CoLendingPartner p) {
        return new CoLendingRules.Partner(p.getCode(), CoLendingRules.split(p.getProducts()), p.getMaxTicket().doubleValue(), CoLendingRules.split(p.getAllowedBands()),
                p.isNtcAllowed(), p.getMinVintageYears().doubleValue(), p.isPslOnly(), p.getPartnerShare().doubleValue(), p.getPartnerRate().doubleValue());
    }

    record Cand(LoanApplication a, SanctionRecord s, DecisionRecord d, CoLendingRules.Loan loan) {}

    List<Cand> candidates(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<Cand> out = new ArrayList<>();
        for (LoanApplication a : apps.findAllByOrderByIdDesc()) {
            if (!Set.of("SANCTIONED", "DISBURSED").contains(a.getAppState())) continue;
            if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) continue;
            SanctionRecord s = sanctions.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "APPROVED").orElse(null);
            DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
            if (s == null || d == null) continue;
            boolean ntc = d.getModelVersion() != null && d.getModelVersion().contains("NTC");
            boolean udyam = documents.findByApplicationIdOrderByIdAsc(a.getId()).stream().anyMatch(x -> "UDYAM".equals(x.getDocType()));
            boolean psl = "MICROFINANCE".equals(a.getSegment()) || "MSME".equals(a.getSegment()) && udyam;
            double vintage = a.getBusinessVintageYears() == null ? 0 : a.getBusinessVintageYears().doubleValue();
            out.add(new Cand(a, s, d, new CoLendingRules.Loan(a.getProductCode(), s.getAmount().doubleValue(), s.getRate().doubleValue(), d.getRiskBand(), ntc, vintage, psl)));
        }
        return out;
    }

    public Map<String, Object> pool(CurrentUser u) {
        List<Cand> cs = candidates(u);
        Map<Long, List<CoLendingAssignment>> assigned = new HashMap<>();
        for (CoLendingAssignment x : assignments.findAll()) assigned.computeIfAbsent(x.getApplicationId(), k -> new ArrayList<>()).add(x);
        List<Map<String, Object>> byPartner = new ArrayList<>();
        for (CoLendingPartner p : partners.findByStatusOrderByIdAsc("ACTIVE")) {
            CoLendingRules.Partner r = rules(p);
            List<Map<String, Object>> eligible = new ArrayList<>();
            Map<String, Integer> why = new TreeMap<>();
            for (Cand c : cs) {
                List<CoLendingAssignment> mine = assigned.getOrDefault(c.a().getId(), List.of()).stream().filter(x -> !"REJECTED".equals(x.getStatus()) && !"WITHDRAWN".equals(x.getStatus())).toList();
                List<String> rs = new ArrayList<>(CoLendingRules.reasons(r, c.loan()));
                if (!mine.isEmpty() && mine.stream().noneMatch(x -> x.getPartnerId().equals(p.getId()))) rs.add("Already with another partner");
                if (!rs.isEmpty()) { rs.forEach(x -> why.merge(x, 1, Integer::sum)); continue; }
                double amt = c.loan().amount();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("applicationId", c.a().getId());
                m.put("appNo", c.a().getAppNo());
                m.put("borrowerName", c.a().getApplicantName());
                m.put("productCode", c.a().getProductCode());
                m.put("amount", Math.round(amt));
                m.put("partnerAmount", Math.round(amt * r.share()));
                m.put("loanRate", c.loan().rate());
                m.put("nbfcYield", CoLendingRules.nbfcYield(c.loan().rate(), r.share(), r.partnerRate()));
                m.put("band", c.loan().band());
                m.put("psl", c.loan().psl());
                m.put("status", c.a().getAppState());
                mine.stream().filter(x -> x.getPartnerId().equals(p.getId())).findFirst().ifPresent(x -> m.put("assignment", x));
                eligible.add(m);
            }
            double amount = eligible.stream().mapToDouble(m -> ((Number) m.get("amount")).doubleValue()).sum();
            double partnerAmount = eligible.stream().mapToDouble(m -> ((Number) m.get("partnerAmount")).doubleValue()).sum();
            double weighted = eligible.stream().mapToDouble(m -> ((Number) m.get("amount")).doubleValue() * ((Number) m.get("loanRate")).doubleValue()).sum();
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("partner", p);
            pm.put("eligible", eligible);
            pm.put("eligibleCount", eligible.size());
            pm.put("eligibleAmount", Math.round(amount));
            pm.put("partnerAmount", Math.round(partnerAmount));
            pm.put("blendedRate", amount == 0 ? null : Math.round(weighted / amount * 100) / 100.0);
            pm.put("nbfcYield", amount == 0 ? null : CoLendingRules.nbfcYield(weighted / amount, p.getPartnerShare().doubleValue(), p.getPartnerRate().doubleValue()));
            pm.put("notEligible", why);
            byPartner.add(pm);
        }
        return Map.of("candidates", cs.size(), "partners", byPartner);
    }

    @Transactional
    public Map<String, Object> submit(Submit r, CurrentUser by) {
        CoLendingPartner p = partners.findById(r.partnerId() == null ? -1 : r.partnerId()).orElseThrow(() -> ApiException.notFound("Partner"));
        if (!"ACTIVE".equals(p.getStatus())) throw ApiException.conflict("PARTNER_INACTIVE", "The partner is not active");
        if (r.applicationIds() == null || r.applicationIds().isEmpty()) throw ApiException.unprocessable("NOTHING_SELECTED", "Select the loans to send");
        Map<Long, Cand> byId = new HashMap<>();
        candidates(by).forEach(c -> byId.put(c.a().getId(), c));
        String batch = "CL-" + p.getCode() + "-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.of("Asia/Kolkata")).format(Instant.now());
        List<CoLendingAssignment> made = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(r.applicationIds())) {
            Cand c = byId.get(id);
            if (c == null) throw ApiException.unprocessable("NOT_A_CANDIDATE", "Application " + id + " is not sanctioned or not visible");
            List<String> why = CoLendingRules.reasons(rules(p), c.loan());
            if (!why.isEmpty()) throw ApiException.unprocessable("NOT_ELIGIBLE", c.a().getAppNo() + ": " + String.join("; ", why));
            boolean taken = assignments.findByApplicationId(id).stream().anyMatch(x -> !Set.of("REJECTED", "WITHDRAWN").contains(x.getStatus()));
            if (taken) throw ApiException.conflict("ALREADY_SENT", c.a().getAppNo() + " is already with a partner");
            CoLendingAssignment x = assignments.findByApplicationId(id).stream().filter(y -> y.getPartnerId().equals(p.getId())).findFirst().orElseGet(CoLendingAssignment::new);
            double amt = c.loan().amount(), share = p.getPartnerShare().doubleValue();
            x.setPartnerId(p.getId());
            x.setApplicationId(id);
            x.setAmount(BigDecimal.valueOf(amt).setScale(2, RoundingMode.HALF_UP));
            x.setPartnerAmount(BigDecimal.valueOf(amt * share).setScale(2, RoundingMode.HALF_UP));
            x.setLoanRate(BigDecimal.valueOf(c.loan().rate()));
            x.setPartnerRate(p.getPartnerRate());
            x.setNbfcYield(BigDecimal.valueOf(CoLendingRules.nbfcYield(c.loan().rate(), share, p.getPartnerRate().doubleValue())));
            x.setStatus("SUBMITTED");
            x.setBatch(batch);
            x.setSubmittedBy(by.username());
            x.setSubmittedAt(Instant.now());
            x.setDecidedBy(null);
            x.setDecidedAt(null);
            made.add(assignments.save(x));
        }
        audit.record(by, "colending.submitted", "COLENDING", batch, null, p.getCode() + " · " + made.size() + " loan(s)");
        return Map.of("batch", batch, "assignments", made);
    }

    static String q(String s) { return s == null ? "" : '"' + s.replace("\"", "\"\"") + '"'; }

    /** Partner loan file for a batch (CSV; contains PAN for the partner's own checks; audited). */
    public String file(String batch, CurrentUser by) {
        List<CoLendingAssignment> xs = assignments.findByBatchOrderByIdAsc(batch);
        if (xs.isEmpty()) throw ApiException.notFound("Batch " + batch);
        CoLendingPartner p = partners.findById(xs.get(0).getPartnerId()).orElseThrow();
        StringBuilder b = new StringBuilder("Batch,PartnerCode,AppNo,LoanRef,BorrowerName,PAN,Product,Segment,SanctionedAmount,PartnerSharePct,PartnerAmount,BorrowerRate,PartnerRate,OriginatorRate,TenureMonths,RiskBand,PD,DisbursedOn\n");
        for (CoLendingAssignment x : xs) {
            LoanApplication a = apps.findById(x.getApplicationId()).orElseThrow();
            DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
            var l = loans.findByApplicationId(a.getId()).orElse(null);
            b.append(String.join(",", batch, p.getCode(), a.getAppNo(), l == null ? "" : l.getLoanRef(), q(a.getApplicantName()), Objects.toString(a.getPan(), ""), a.getProductCode(),
                    a.getSegment(), x.getAmount().toPlainString(), String.valueOf(Math.round(p.getPartnerShare().doubleValue() * 100)), x.getPartnerAmount().toPlainString(),
                    x.getLoanRate().toPlainString(), x.getPartnerRate().toPlainString(), x.getNbfcYield().toPlainString(), String.valueOf(a.getTenureMonths()),
                    d == null ? "" : Objects.toString(d.getRiskBand(), ""), d == null || d.getPd() == null ? "" : d.getPd().toPlainString(),
                    l == null ? "" : l.getDisbursedOn().toString())).append('\n');
        }
        audit.record(by, "colending.file_generated", "COLENDING", batch, null, xs.size() + " rows (PAN included)");
        return b.toString();
    }

    @Transactional
    public CoLendingAssignment decide(Long id, Decide r, CurrentUser by) {
        CoLendingAssignment x = assignments.findById(id).orElseThrow(() -> ApiException.notFound("Assignment"));
        if (!"SUBMITTED".equals(x.getStatus())) throw ApiException.conflict("DECIDED", "Already " + x.getStatus().toLowerCase());
        if (!r.approve() && (r.note() == null || r.note().trim().length() < 5)) throw ApiException.unprocessable("REASON_REQUIRED", "Record the partner's reason");
        x.setStatus(r.approve() ? "APPROVED" : "REJECTED");
        x.setPartnerRef(r.partnerRef());
        x.setNote(r.note());
        x.setDecidedBy(by.username());
        x.setDecidedAt(Instant.now());
        assignments.save(x);
        audit.record(by, "colending." + x.getStatus().toLowerCase(), "COLENDING", x.getBatch(), x.getApplicationId(), Objects.toString(r.partnerRef(), "") + " " + Objects.toString(r.note(), ""));
        return x;
    }

    public List<Map<String, Object>> assignments(CurrentUser u) {
        Map<Long, CoLendingPartner> ps = new HashMap<>();
        partners.findAll().forEach(p -> ps.put(p.getId(), p));
        Set<Long> vis = scope.visibleBranches(u);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CoLendingAssignment x : assignments.findAllByOrderByIdDesc()) {
            LoanApplication a = apps.findById(x.getApplicationId()).orElse(null);
            if (a == null || vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) continue;
            out.add(Map.of("assignment", x, "partner", ps.get(x.getPartnerId()).getCode(), "appNo", a.getAppNo(), "borrowerName", a.getApplicantName()));
        }
        return out;
    }

    public List<CoLendingPartner> partners() { return partners.findAll().stream().sorted(Comparator.comparing(CoLendingPartner::getId)).toList(); }

    @Transactional
    public CoLendingPartner updatePartner(Long id, Map<String, Object> r, CurrentUser by) {
        CoLendingPartner p = partners.findById(id).orElseThrow(() -> ApiException.notFound("Partner"));
        Object note = r.get("note");
        if (note == null || note.toString().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Record the agreement reference or reason (at least 10 characters)");
        if (r.get("partnerShare") != null) {
            double s = ((Number) r.get("partnerShare")).doubleValue();
            if (s < CoLendingRules.MIN_RETENTION || s > 1 - CoLendingRules.MIN_RETENTION)
                throw ApiException.unprocessable("BAD_SHARE", "Each lender must keep at least 10% of every loan (RBI co-lending directions)");
            p.setPartnerShare(BigDecimal.valueOf(s));
        }
        if (r.get("partnerRate") != null) p.setPartnerRate(BigDecimal.valueOf(((Number) r.get("partnerRate")).doubleValue()));
        if (r.get("maxTicket") != null) p.setMaxTicket(BigDecimal.valueOf(((Number) r.get("maxTicket")).doubleValue()));
        if (r.get("allowedBands") != null) p.setAllowedBands(r.get("allowedBands").toString());
        if (r.get("products") != null) p.setProducts(r.get("products").toString());
        if (r.get("ntcAllowed") != null) p.setNtcAllowed(Boolean.parseBoolean(r.get("ntcAllowed").toString()));
        if (r.get("pslOnly") != null) p.setPslOnly(Boolean.parseBoolean(r.get("pslOnly").toString()));
        if (r.get("minVintageYears") != null) p.setMinVintageYears(BigDecimal.valueOf(((Number) r.get("minVintageYears")).doubleValue()));
        if (r.get("status") != null) p.setStatus(r.get("status").toString());
        p.setUpdatedBy(by.username());
        p.setUpdatedAt(Instant.now());
        partners.save(p);
        audit.record(by, "colending.partner_updated", "COLENDING_PARTNER", p.getCode(), null, note.toString());
        return p;
    }
}
