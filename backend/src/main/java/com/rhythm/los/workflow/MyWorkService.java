package com.rhythm.los.workflow;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.integration.IntegrationLogRepository;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Task queue derived from application states, so nothing can fall between queues. */
@Service
public class MyWorkService {
    private final LoanApplicationRepository apps;
    private final IntegrationLogRepository logs;
    private final com.rhythm.los.vkyc.VkycSessionRepository vkyc;
    private final com.rhythm.los.policy.PolicyRepository policies;
    private final com.rhythm.los.flow.WorkflowService workflow;
    private final com.rhythm.los.flow.WorkflowDesignService design;
    private final com.rhythm.los.flow.StageAccess access;
    private final com.rhythm.los.org.DataScope scope;
    private java.util.function.Predicate<LoanApplication> sentBack = a -> false;

    private com.rhythm.los.collateral.CollateralReviewRepository colReviews;
    private com.rhythm.los.collateral.CollateralRepository collaterals;

    @org.springframework.beans.factory.annotation.Autowired
    void setCollateral(com.rhythm.los.collateral.CollateralReviewRepository r, com.rhythm.los.collateral.CollateralRepository c) { this.colReviews = r; this.collaterals = c; }

    private com.rhythm.los.party.ApplicationPartyRepository partyRepo;
    private com.rhythm.los.party.PartyRequirementRepository partyRequests;

    @org.springframework.beans.factory.annotation.Autowired
    void setParties(com.rhythm.los.party.ApplicationPartyRepository p, com.rhythm.los.party.PartyRequirementRepository q) { this.partyRepo = p; this.partyRequests = q; }

    @org.springframework.beans.factory.annotation.Autowired
    void setSanctions(@org.springframework.context.annotation.Lazy com.rhythm.los.sanction.SanctionService s) { this.sentBack = s::awaitingReassessment; }

    public MyWorkService(LoanApplicationRepository apps, IntegrationLogRepository logs, com.rhythm.los.vkyc.VkycSessionRepository vkyc,
                         com.rhythm.los.policy.PolicyRepository policies, com.rhythm.los.flow.WorkflowService workflow,
                         com.rhythm.los.flow.WorkflowDesignService design, com.rhythm.los.flow.StageAccess access,
                         com.rhythm.los.org.DataScope scope) {
        this.access = access;
        this.scope = scope;
        this.workflow = workflow;
        this.design = design;
        this.policies = policies;
        this.apps = apps;
        this.logs = logs;
        this.vkyc = vkyc;
    }

    public record Task(Long applicationId, String appNo, String applicant, String product, double amount,
                       String task, String action, long ageHours, int slaHours) {}

    public List<Task> tasks(CurrentUser u) {
        Role r = u.role();
        List<Task> out = new ArrayList<>();
        java.util.Set<Long> visible = scope.visibleBranches(u);
        for (LoanApplication a : apps.findAllByOrderByIdDesc()) {
            String st = a.getAppState();
            if (List.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(st)) continue;
            if (!com.rhythm.los.org.DataScope.canSee(visible, a)) continue;
            switch (r) {
                case SALES -> {
                    if ("DRAFT".equals(st)) add(out, a, "Complete and submit the application", "submit", 24);
                    if ("SANCTIONED".equals(a.getSanctionState())) add(out, a, "Get the borrower to accept the KFS", "acceptKfs", 48);
                    if ("DEFICIENT".equals(a.getDocsState())) add(out, a, "Collect missing or clearer documents", "uploadDocument", 24);
                }
                case CREDIT_OFFICER, CREDIT_MANAGER, CRO -> {
                    String s = a.getSanctionState();
                    if (s.startsWith("PENDING_") && Delegation.rank(s.substring(8)) == r.sanctionLevel())
                        add(out, a, "Sanction decision (" + s.substring(8) + ")", "sanction", 24);
                }
                case FRAUD_ANALYST -> {
                    if ("REVIEW".equals(a.getFraudState())) add(out, a, "Fraud referral: investigate", "fraudDisposition", 24);
                }
                default -> { }
            }
            // processing tasks go to the role the product's workflow assigns to each stage
            if (List.of("SUBMITTED", "UNDERWRITING").contains(st) || "READY".equals(a.getDisbState()) || "FAILED".equals(a.getDisbState())) {
                boolean intake = access.canAny(a, u, "kyc", "docs");
                if ("SUBMITTED".equals(st) && intake) add(out, a, "Run automated checks", "process", 4);
                if ("REVIEW".equals(a.getKycState()) && access.can(a, "kyc", u)) add(out, a, "KYC review: do video KYC and resolve", "resolveKyc", 8);
                if (List.of("FAILED", "PARTIAL").contains(a.getDataState()) && intake) add(out, a, "Data fetch failed: retry from integration log", "fetchData", 4);
                if ("DEFICIENT".equals(a.getDocsState()) && r != Role.SALES && access.can(a, "docs", u)) add(out, a, "Documents deficient: follow up", "verifyDocuments", 24);
                if ("IN_REVIEW".equals(a.getDocsState()) && access.can(a, "docs", u)) add(out, a, "Document review: AI was not sure", "reviewDocuments", 4);
                if ("REQUIRED".equals(a.getFieldState()) && !"NOT_STARTED".equals(a.getKycState()) && access.can(a, "field", u)) add(out, a, "Field visit required", "fieldVisit", 48);
                if ("UNDERWRITING".equals(st) && "PENDING".equals(a.getDecisionState()) && "NOT_STARTED".equals(a.getSanctionState())
                        && access.can(a, "uw", u) && sentBack.test(a)) add(out, a, "Sent back from sanction: re-assess and re-run the decision", "runDecision", 24);
                if ("READY".equals(a.getDisbState()) && access.can(a, "disb", u)) add(out, a, "Disburse", "disburse", 8);
                if ("FAILED".equals(a.getDisbState()) && access.can(a, "disb", u)) add(out, a, "Disbursement failed: fix and retry", "retryDisbursement", 4);
            }
        }
        for (var t : workflow.tasks(u)) {
            LoanApplication a = (LoanApplication) t.get("application");
            if (!com.rhythm.los.org.DataScope.canSee(visible, a)) continue;
            out.add(0, new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(),
                    (String) t.get("task"), "stage", (Long) t.get("ageHours"), (Integer) t.get("slaHours")));
        }
        if (r == Role.CRO) for (var wv : design.pending()) {
            if (u.username().equals(wv.getCreatedBy())) continue;
            out.add(0, new Task(null, wv.getProductCode() + " v" + wv.getVersion(), null, null, 0, "Workflow " + wv.getProductCode() + " v" + wv.getVersion() + " waiting for your approval",
                    "workflow", Duration.between(wv.getSubmittedAt(), Instant.now()).toHours(), 48));
        }
        for (var pv : policies.findAllByOrderByIdDesc()) {
            if (r == Role.CRO && "PENDING_APPROVAL".equals(pv.getStatus()) && !u.username().equals(pv.getCreatedBy()))
                out.add(0, new Task(null, pv.getVersion(), null, null, 0, "Credit policy " + pv.getVersion() + " waiting for your approval", "policy",
                        Duration.between(pv.getSubmittedAt(), Instant.now()).toHours(), 48));
            if ((r == Role.CREDIT_MANAGER || r == Role.CRO || r == Role.ADMIN) && "DRAFT".equals(pv.getStatus()) && u.username().equals(pv.getCreatedBy()))
                out.add(0, new Task(null, pv.getVersion(), null, null, 0, "Credit policy draft " + pv.getVersion() + ": simulate and send for approval", "policy",
                        Duration.between(pv.getCreatedAt(), Instant.now()).toHours(), 72));
        }
        if (r == Role.ADMIN || r == Role.OPERATIONS) {
            for (var s : vkyc.findByStatusInOrderByIdAsc(List.of("JOINED", "COMPLETED"))) {
                if ("COMPLETED".equals(s.getStatus()) && !u.username().equals(s.getOfficer())) continue;
                apps.findById(s.getApplicationId()).ifPresent(a -> out.add(0, new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(),
                        a.getLoanAmount().doubleValue(), "JOINED".equals(s.getStatus()) ? "Video KYC: customer is waiting on the call" : "Video KYC: record your decision",
                        "videoKyc", Duration.between("JOINED".equals(s.getStatus()) ? s.getJoinedAt() : s.getCallEndedAt(), Instant.now()).toHours(), 1)));
            }
            long dlq = logs.countByStatus("DLQ");
            if (dlq > 0) out.add(0, new Task(null, null, null, null, 0, dlq + " integration call(s) in the dead-letter queue", "integrations", 0, 4));
        }
        // collateral reviews for the legal reviewer, the valuer, field officers and credit (SCR-147)
        if (colReviews != null) {
            java.util.Set<Long> vis = scope.visibleBranches(u);
            for (var rv : colReviews.findByStatusOrderByDueAtAsc("OPEN")) {
                if (!r.name().equals(rv.getRole()) && r != Role.ADMIN) continue;
                var c = collaterals.findById(rv.getCollateralId()).orElse(null);
                if (c == null) continue;
                apps.findById(c.getApplicationId()).filter(a -> vis == null || a.getBranchId() == null || vis.contains(a.getBranchId())).ifPresent(a ->
                        out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(),
                                "Collateral " + c.getColNo() + " · " + rv.getRuleId() + ": " + rv.getReason(), "collateralReview:" + c.getId(),
                                Duration.between(rv.getCreatedAt(), Instant.now()).toHours(), rv.getSlaHours())));
            }
        }
        // co-applicants and guarantors: consent and signatures for sales, KYC reviews for operations, requests from credit
        if (partyRepo != null) {
            java.util.Set<Long> vis = scope.visibleBranches(u);
            boolean front = r == Role.SALES || r == Role.OPERATIONS || r == Role.ADMIN;
            for (var p : partyRepo.findAll()) {
                if ("REMOVED".equals(p.getStatus())) continue;
                LoanApplication a = apps.findById(p.getApplicationId()).orElse(null);
                if (a == null || List.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(a.getAppState()) || !com.rhythm.los.org.DataScope.canSee(vis, a)) continue;
                String who = p.getName() + " (" + com.rhythm.los.party.PartyRules.label(p.getRole()) + ")";
                long age = Duration.between(p.getUpdatedAt(), Instant.now()).toHours();
                if (front && p.getConsentAt() == null)
                    out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(),
                            who + ": get consent (invite link or OTP in the branch)", "parties", age, 48));
                if ((r == Role.OPERATIONS || r == Role.ADMIN) && "REVIEW".equals(p.getKycState()))
                    out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(),
                            who + ": KYC review", "parties", age, 8));
                if (front && p.getSignedAt() == null && List.of("SANCTIONED", "KFS_ACCEPTED").contains(a.getSanctionState()))
                    out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(),
                            who + ": sign by OTP before disbursement", "parties", age, 48));
            }
            if (front) for (var q : partyRequests.findAll()) {
                if (!"OPEN".equals(q.getStatus())) continue;
                apps.findById(q.getApplicationId()).filter(a -> !List.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(a.getAppState())
                        && com.rhythm.los.org.DataScope.canSee(vis, a)).ifPresent(a -> out.add(0, new Task(a.getId(), a.getAppNo(), a.getApplicantName(),
                        a.getProductCode(), a.getLoanAmount().doubleValue(), "Credit asked for a " + com.rhythm.los.party.PartyRules.label(q.getRole()) + ": " + q.getReason(),
                        "parties", Duration.between(q.getRequestedAt(), Instant.now()).toHours(), 48)));
            }
        }
        return out;
    }

    private static void add(List<Task> out, LoanApplication a, String task, String action, int sla) {
        long age = Duration.between(a.getUpdatedAt(), Instant.now()).toHours();
        out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(), task, action, age, sla));
    }
}
