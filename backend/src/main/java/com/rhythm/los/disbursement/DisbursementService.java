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

    private com.rhythm.los.book.LoanBookService book;

    @org.springframework.beans.factory.annotation.Autowired
    void setBook(com.rhythm.los.book.LoanBookService b) { this.book = b; }

    public List<Disbursement> list(Long appId) { return repo.findByApplicationIdOrderByIdAsc(appId); }

    private com.rhythm.los.docgen.DocPackService docPacks;
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
    void setDocPacks(@org.springframework.context.annotation.Lazy com.rhythm.los.docgen.DocPackService d) { this.docPacks = d; }

    @Transactional
    public Disbursement disburse(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!"READY".equals(a.getDisbState())) throw ApiException.conflict("NOT_READY", "Disbursement is " + a.getDisbState());
        workflow.requireNothingPendingBefore(a, "disb");
        // pre-disbursal re-check (FR-DIS-04): consents still valid and no new links to other applicants since the decision
        List<String> recheck = new java.util.ArrayList<>(consents.invalidForDisbursement(a));
        recheck.addAll(newLinksSinceDecision(a));
        recheck.addAll(sanctions.partyBlockers(a, "DISBURSE"));
        if (docPacks != null) recheck.addAll(docPacks.blockers(a));
        if (assets != null) recheck.addAll(assets.disbursementBlockers(a));
        if (trade != null) recheck.addAll(trade.activationBlockers(a));
        if (plan != null) recheck.addAll(plan.disbursementBlockers(a));
        if (pledge != null) recheck.addAll(pledge.disbursementBlockers(a));
        if (!recheck.isEmpty()) throw ApiException.conflict("PRE_DISBURSAL_CHECK", "Pre-disbursal check failed: " + String.join("; ", recheck));
        Kfs kfs = sanctions.kfs(appId);
        if (trade != null && trade.isTrade(a)) return activateLimit(a, kfs, by);

        Disbursement d = new Disbursement();
        d.setApplicationId(appId);
        d.setGrossAmount(BigDecimal.valueOf(kfs.sanctionedAmount()));
        d.setDeductions(BigDecimal.valueOf(kfs.processingFee() + kfs.gstOnFee() + kfs.insurance()));
        d.setNetAmount(BigDecimal.valueOf(kfs.netDisbursed()));
        // asset finance pays the dealer or supplier; loans released in tranches pay the first release; other loans pay the borrower
        LoanApplication payee = assets == null ? a : assets.payee(a).orElse(a);
        boolean toDealer = payee != a;
        var first = plan == null ? java.util.Optional.<com.rhythm.los.plan.PlanService.FirstRelease>empty() : plan.firstRelease(a, kfs);
        double payAmount = kfs.netDisbursed();
        if (first.isPresent()) {
            payee = first.get().payee();
            toDealer = false;
            payAmount = first.get().net();
            d.setGrossAmount(BigDecimal.valueOf(first.get().gross()));
            d.setDeductions(BigDecimal.valueOf(first.get().deductions()));
            d.setNetAmount(BigDecimal.valueOf(payAmount));
        }
        d.setBeneficiaryAccount(mask(payee.getBankAccountNo()));
        d.setIfsc(payee.getBankIfsc());
        d.setActor(by.username());

        final LoanApplication to = payee;
        final double amount = payAmount;
        var pd = gateway.call(appId, bank.name(), "bank.penny_drop", () -> bank.pennyDrop(to),
                r -> "name " + r.beneficiaryName() + " match " + r.nameMatch(), null);
        if (!pd.ok()) return fail(a, d, "Penny drop failed: " + pd.error(), by);
        PennyDropResult p = pd.value();
        d.setNameMatchScore(BigDecimal.valueOf(p.nameMatch()));
        if (!p.accountValid() || p.nameMatch() < MIN_NAME_MATCH) {
            return fail(a, d, "Beneficiary name '" + p.beneficiaryName() + "' does not match the " + (toDealer ? "dealer" : first.isPresent() && !first.get().toBorrower() ? "payee" : "borrower") + " (match " + p.nameMatch() + ")", by);
        }

        var po = gateway.call(appId, payout.name(), "payout.transfer", () -> payout.pay(to, amount, a.getAppNo()),
                r -> r.success() ? "UTR " + r.utr() : "failed " + r.failureReason(), null);
        if (!po.ok() || !po.value().success()) {
            return fail(a, d, "Payout failed: " + (po.ok() ? po.value().failureReason() : po.error()), by);
        }
        PayoutResult r = po.value();
        d.setStatus("SUCCESS");
        d.setUtr(r.utr());
        d.setLmsPayload(Json.write(lmsPayload(a, kfs, r.utr())));
        d = repo.save(d);
        apps.transition(a, Domain.DISB, "DISBURSED", "disbursement.completed", by, first.isPresent()
                ? "UTR " + r.utr() + " · first release ₹" + Math.round(amount) + " paid to " + first.get().payeeName() + "; later releases in the Plan tab"
                : "UTR " + r.utr() + " · net ₹" + kfs.netDisbursed() + (toDealer ? " paid to " + payee.getApplicantName() : ""));
        apps.transition(a, Domain.APP, "DISBURSED", "application.disbursed", by, "Loan booking file sent to LMS");
        if (book != null) book.onDisbursed(a, kfs, "LN-" + a.getAppNo().replace("APP-", ""));
        if (assets != null && toDealer) assets.onDisbursed(a);
        if (first.isPresent()) plan.onFirstRelease(a, r.utr(), amount, first.get().payeeName(), by.username());
        return d;
    }

    /**
     * A revolving trade-finance limit: the borrower's account is verified (it receives the drawdowns on receivables and vendor
     * programmes) and the limit becomes available; money moves only when an invoice is funded.
     */
    private Disbursement activateLimit(LoanApplication a, Kfs kfs, CurrentUser by) {
        Disbursement d = new Disbursement();
        d.setApplicationId(a.getId());
        d.setGrossAmount(BigDecimal.valueOf(kfs.sanctionedAmount()));
        d.setDeductions(BigDecimal.valueOf(kfs.processingFee() + kfs.gstOnFee()));
        d.setNetAmount(BigDecimal.ZERO);
        d.setBeneficiaryAccount(mask(a.getBankAccountNo()));
        d.setIfsc(a.getBankIfsc());
        d.setActor(by.username());
        var pd = gateway.call(a.getId(), bank.name(), "bank.penny_drop", () -> bank.pennyDrop(a),
                r -> "name " + r.beneficiaryName() + " match " + r.nameMatch(), null);
        if (!pd.ok()) return fail(a, d, "Penny drop failed: " + pd.error(), by);
        PennyDropResult p = pd.value();
        d.setNameMatchScore(BigDecimal.valueOf(p.nameMatch()));
        if (!p.accountValid() || p.nameMatch() < MIN_NAME_MATCH)
            return fail(a, d, "Beneficiary name '" + p.beneficiaryName() + "' does not match the borrower (match " + p.nameMatch() + ")", by);
        var l = trade.activate(a, kfs.sanctionedAmount(), kfs.ratePa(), by.username());
        d.setStatus("SUCCESS");
        d.setUtr("LIMIT " + l.limitNo);
        Map<String, Object> lms = new LinkedHashMap<>();
        lms.put("externalRef", a.getAppNo());
        lms.put("type", "REVOLVING_LIMIT");
        lms.put("limitNo", l.limitNo);
        lms.put("limit", kfs.sanctionedAmount());
        lms.put("ratePa", kfs.ratePa());
        lms.put("validTo", String.valueOf(l.validTo));
        lms.put("fees", Map.of("processing", kfs.processingFee(), "gst", kfs.gstOnFee()));
        d.setLmsPayload(Json.write(lms));
        d = repo.save(d);
        apps.transition(a, Domain.DISB, "DISBURSED", "disbursement.completed", by, "Limit " + l.limitNo + " of ₹" + Math.round(kfs.sanctionedAmount()) + " activated to " + l.validTo + "; drawdowns against invoices");
        apps.transition(a, Domain.APP, "DISBURSED", "application.disbursed", by, "Revolving limit activated; drawdowns in Trade finance");
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
