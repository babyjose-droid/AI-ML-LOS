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

    public MyWorkService(LoanApplicationRepository apps, IntegrationLogRepository logs) {
        this.apps = apps;
        this.logs = logs;
    }

    public record Task(Long applicationId, String appNo, String applicant, String product, double amount,
                       String task, String action, long ageHours, int slaHours) {}

    public List<Task> tasks(CurrentUser u) {
        Role r = u.role();
        List<Task> out = new ArrayList<>();
        for (LoanApplication a : apps.findAllByOrderByIdDesc()) {
            String st = a.getAppState();
            if (List.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(st)) continue;
            switch (r) {
                case SALES -> {
                    if ("DRAFT".equals(st)) add(out, a, "Complete and submit the application", "submit", 24);
                    if ("SANCTIONED".equals(a.getSanctionState())) add(out, a, "Get the borrower to accept the KFS", "acceptKfs", 48);
                    if ("DEFICIENT".equals(a.getDocsState())) add(out, a, "Collect missing or clearer documents", "uploadDocument", 24);
                }
                case OPERATIONS, ADMIN -> {
                    if ("SUBMITTED".equals(st)) add(out, a, "Run automated checks", "process", 4);
                    if ("REVIEW".equals(a.getKycState())) add(out, a, "KYC review: do video KYC and resolve", "resolveKyc", 8);
                    if (List.of("FAILED", "PARTIAL").contains(a.getDataState())) add(out, a, "Data fetch failed: retry from integration log", "fetchData", 4);
                    if ("DEFICIENT".equals(a.getDocsState())) add(out, a, "Documents deficient: follow up", "verifyDocuments", 24);
                    if ("IN_REVIEW".equals(a.getDocsState())) add(out, a, "Document review: AI was not sure", "reviewDocuments", 4);
                    if ("REQUIRED".equals(a.getFieldState()) && !"SUBMITTED".equals(st)) add(out, a, "Field visit required", "fieldVisit", 48);
                    if ("READY".equals(a.getDisbState())) add(out, a, "Disburse", "disburse", 8);
                    if ("FAILED".equals(a.getDisbState())) add(out, a, "Disbursement failed: fix and retry", "retryDisbursement", 4);
                }
                case CREDIT_OFFICER, CREDIT_MANAGER, CRO -> {
                    String s = a.getSanctionState();
                    if (s.startsWith("PENDING_") && Delegation.rank(s.substring(8)) == r.sanctionLevel())
                        add(out, a, "Sanction decision (" + s.substring(8) + ")", "sanction", 24);
                    if (r == Role.CREDIT_OFFICER && "REQUIRED".equals(a.getFieldState()) && !"SUBMITTED".equals(st))
                        add(out, a, "Field visit required", "fieldVisit", 48);
                }
                case FRAUD_ANALYST -> {
                    if ("REVIEW".equals(a.getFraudState())) add(out, a, "Fraud referral: investigate", "fraudDisposition", 24);
                }
                default -> { }
            }
        }
        if (r == Role.ADMIN || r == Role.OPERATIONS) {
            long dlq = logs.countByStatus("DLQ");
            if (dlq > 0) out.add(0, new Task(null, null, null, null, 0, dlq + " integration call(s) in the dead-letter queue", "integrations", 0, 4));
        }
        return out;
    }

    private static void add(List<Task> out, LoanApplication a, String task, String action, int sla) {
        long age = Duration.between(a.getUpdatedAt(), Instant.now()).toHours();
        out.add(new Task(a.getId(), a.getAppNo(), a.getApplicantName(), a.getProductCode(), a.getLoanAmount().doubleValue(), task, action, age, sla));
    }
}
