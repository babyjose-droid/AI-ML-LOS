package com.rhythm.los.disbursement;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.integration.VendorModels.PayoutResult;
import com.rhythm.los.integration.VendorModels.PennyDropResult;
import com.rhythm.los.integration.Vendors;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.SanctionService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Disbursement: penny-drop name check, payout, then a loan-booking payload for the NBFC's LMS.
 * Rhythm does not service loans; the LMS owns the loan account after this point.
 */
@Service
public class DisbursementService {
    private static final double MIN_NAME_MATCH = 0.80;

    private final ApplicationService apps;
    private final SanctionService sanctions;
    private final DisbursementRepository repo;
    private final IntegrationGateway gateway;
    private final Vendors.BankVerification bank;
    private final Vendors.PayoutProvider payout;
    private final com.rhythm.los.flow.WorkflowService workflow;
    private final com.rhythm.los.consent.ConsentService consents;
    private final com.rhythm.los.application.LoanApplicationRepository appRepo;
    private final com.rhythm.los.decision.DecisionRecordRepository decisions;

    /** Other PANs now sharing this mobile number, compared with the count the decision saw. */
    List<String> newLinksSinceDecision(LoanApplication a) {
        long now = appRepo.countOtherPansWithMobile(a.getMobile(), a.getPan());
        long then = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId())
                .map(d -> { try { return com.rhythm.los.common.Json.MAPPER.readTree(d.getInputJson()).path("fraud").path("dupLinks").asLong(0); } catch (Exception e) { return 0L; } })
                .orElse(0L);
        return now > then ? List.of("the mobile number is now also used by " + (now - then) + " more applicant(s); send back to credit") : List.of();
    }

    public DisbursementService(ApplicationService apps, SanctionService sanctions, DisbursementRepository repo,
                               IntegrationGateway gateway, Vendors.BankVerification bank, Vendors.PayoutProvider payout,
                               com.rhythm.los.flow.WorkflowService workflow, com.rhythm.los.consent.ConsentService consents,
                               com.rhythm.los.application.LoanApplicationRepository appRepo, com.rhythm.los.decision.DecisionRecordRepository decisions) {
        this.workflow = workflow;
        this.consents = consents;
        this.appRepo = appRepo;
        this.decisions = decisions;
        this.apps = apps;
        this.sanctions = sanctions;
        this.repo = repo;
        this.gateway = gateway;
        this.bank = bank;
        this.payout = payout;
    }

    public List<Disbursement> list(Long appId) { return repo.findByApplicationIdOrderByIdAsc(appId); }

    @Transactional
    public Disbursement disburse(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!"READY".equals(a.getDisbState())) throw ApiException.conflict("NOT_READY", "Disbursement is " + a.getDisbState());
        workflow.requireNothingPendingBefore(a, "disb");
        // pre-disbursal re-check (FR-DIS-04): consents still valid and no new links to other applicants since the decision
        List<String> recheck = new java.util.ArrayList<>(consents.invalidForDisbursement(a));
        recheck.addAll(newLinksSinceDecision(a));
        if (!recheck.isEmpty()) throw ApiException.conflict("PRE_DISBURSAL_CHECK", "Pre-disbursal check failed: " + String.join("; ", recheck));
        Kfs kfs = sanctions.kfs(appId);

        Disbursement d = new Disbursement();
        d.setApplicationId(appId);
        d.setGrossAmount(BigDecimal.valueOf(kfs.sanctionedAmount()));
        d.setDeductions(BigDecimal.valueOf(kfs.processingFee() + kfs.gstOnFee() + kfs.insurance()));
        d.setNetAmount(BigDecimal.valueOf(kfs.netDisbursed()));
        d.setBeneficiaryAccount(mask(a.getBankAccountNo()));
        d.setIfsc(a.getBankIfsc());
        d.setActor(by.username());

        var pd = gateway.call(appId, bank.name(), "bank.penny_drop", () -> bank.pennyDrop(a),
                r -> "name " + r.beneficiaryName() + " match " + r.nameMatch(), null);
        if (!pd.ok()) return fail(a, d, "Penny drop failed: " + pd.error(), by);
        PennyDropResult p = pd.value();
        d.setNameMatchScore(BigDecimal.valueOf(p.nameMatch()));
        if (!p.accountValid() || p.nameMatch() < MIN_NAME_MATCH) {
            return fail(a, d, "Beneficiary name '" + p.beneficiaryName() + "' does not match the borrower (match " + p.nameMatch() + ")", by);
        }

        var po = gateway.call(appId, payout.name(), "payout.transfer", () -> payout.pay(a, kfs.netDisbursed(), a.getAppNo()),
                r -> r.success() ? "UTR " + r.utr() : "failed " + r.failureReason(), null);
        if (!po.ok() || !po.value().success()) {
            return fail(a, d, "Payout failed: " + (po.ok() ? po.value().failureReason() : po.error()), by);
        }
        PayoutResult r = po.value();
        d.setStatus("SUCCESS");
        d.setUtr(r.utr());
        d.setLmsPayload(Json.write(lmsPayload(a, kfs, r.utr())));
        d = repo.save(d);
        apps.transition(a, Domain.DISB, "DISBURSED", "disbursement.completed", by, "UTR " + r.utr() + " · net ₹" + kfs.netDisbursed());
        apps.transition(a, Domain.APP, "DISBURSED", "application.disbursed", by, "Loan booking file sent to LMS");
        return d;
    }

    @Transactional
    public LoanApplication retry(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        apps.transition(a, Domain.DISB, "READY", "disbursement.retry", by, "Ready to retry after correction");
        return a;
    }

    private Disbursement fail(LoanApplication a, Disbursement d, String reason, CurrentUser by) {
        d.setStatus("FAILED");
        d.setFailureReason(reason);
        d = repo.save(d);
        apps.transition(a, Domain.DISB, "FAILED", "disbursement.failed", by, reason);
        return d;
    }

    private static Map<String, Object> lmsPayload(LoanApplication a, Kfs k, String utr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("externalRef", a.getAppNo());
        m.put("customer", Map.of("name", a.getApplicantName(), "pan", com.rhythm.los.common.Mask.pan(a.getPan()), "mobile", a.getMobile()));
        m.put("product", a.getProductCode());
        m.put("principal", k.sanctionedAmount());
        m.put("ratePa", k.ratePa());
        m.put("aprPa", k.aprPa());
        m.put("tenureMonths", k.tenureMonths());
        m.put("emi", k.emi());
        m.put("firstEmiDate", LocalDate.now().plusMonths(1).withDayOfMonth(5).toString());
        m.put("fees", Map.of("processing", k.processingFee(), "gst", k.gstOnFee()));
        m.put("disbursement", Map.of("net", k.netDisbursed(), "utr", utr, "account", mask(a.getBankAccountNo()), "ifsc", a.getBankIfsc()));
        m.put("repaymentMode", k.repaymentMode());
        return m;
    }

    private static String mask(String acc) {
        if (acc == null || acc.length() < 4) return acc;
        return "X".repeat(acc.length() - 4) + acc.substring(acc.length() - 4);
    }
}
