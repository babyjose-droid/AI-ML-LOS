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

@Service
public class SanctionService {
    private final ApplicationService apps;
    private final DecisionService decisions;
    private final SanctionRecordRepository records;
    private final AuditService audit;
    private final String mockOtp;

    public SanctionService(ApplicationService apps, DecisionService decisions, SanctionRecordRepository records,
                           AuditService audit, @Value("${rhythm.kfs.otp}") String mockOtp) {
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
        LoanApplication a = apps.get(appId);
        String level = pendingLevel(a);
        checkAuthority(level, by);
        if (!"CLEAR".equals(a.getFraudState())) throw ApiException.conflict("FRAUD_NOT_CLEARED", "Fraud review must be cleared before sanction");
        DecisionRecord d = decisions.latest(appId).orElseThrow(() -> ApiException.conflict("NO_DECISION", "Run the decision engine first"));
        Product p = apps.product(a);

        BigDecimal amt = amount == null ? d.getRecommendedAmount() : amount;
        boolean override = "REFER".equals(d.getDecision()) || amt.compareTo(d.getRecommendedAmount()) > 0;
        if (override && (note == null || note.trim().length() < 10)) {
            throw ApiException.unprocessable("REASON_REQUIRED", "Approving a referred case or above the recommended amount needs a reason of at least 10 characters");
        }
        if (amt.compareTo(p.getMinAmount()) < 0 || amt.compareTo(p.getMaxAmount()) > 0 || amt.compareTo(a.getLoanAmount()) > 0) {
            throw ApiException.unprocessable("AMOUNT_OUT_OF_RANGE", "Sanction amount must be within product limits and not above the amount applied for");
        }

        Kfs kfs = KfsCalculator.build(a.getAppNo(), a.getApplicantName(), p.getName(), amt.doubleValue(),
                d.getRate().doubleValue(), a.getTenureMonths(), p.getProcessingFeePct().doubleValue());

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
        s = records.save(s);

        apps.transition(a, Domain.SANCTION, "SANCTIONED", "sanction.approved", by,
                level + " · ₹" + amt.toPlainString() + (override ? " · OVERRIDE: " + note : ""));
        apps.transition(a, Domain.APP, "SANCTIONED", "application.sanctioned", by, null);
        if (override) audit.record(by, "sanction.override", "APPLICATION", a.getAppNo(), appId, note);
        return s;
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

    public Kfs kfs(Long appId) {
        return records.findFirstByApplicationIdAndActionOrderByIdDesc(appId, "APPROVED")
                .map(r -> Json.read(r.getKfsJson(), Kfs.class))
                .orElseThrow(() -> ApiException.notFound("Key Fact Statement"));
    }

    @Transactional
    public LoanApplication acceptKfs(Long appId, String otp, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!"SANCTIONED".equals(a.getSanctionState())) throw ApiException.conflict("NOT_SANCTIONED", "Sanction is " + a.getSanctionState());
        if (otp == null || !otp.equals(mockOtp)) throw ApiException.unprocessable("BAD_OTP", "The OTP is not correct");
        SanctionRecord r = records.findFirstByApplicationIdAndActionOrderByIdDesc(appId, "APPROVED").orElseThrow();
        r.setKfsAcceptedAt(Instant.now());
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
