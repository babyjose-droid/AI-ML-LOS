package com.rhythm.los.workflow;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.decision.DecisionService;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Straight-through processing: documents (AI reading), KYC, bank/bureau/GST data, decision. Runs every
 * automatic step that is ready, in order, each in its own
 * transaction, and stops at the first step that needs a person. Returns what happened.
 */
@Service
public class ProcessService {
    private final ApplicationService apps;
    private final DataFetchService data;
    private final DocumentService docs;
    private final DecisionService decisions;

    public ProcessService(ApplicationService apps, DataFetchService data, DocumentService docs, DecisionService decisions) {
        this.apps = apps;
        this.data = data;
        this.docs = docs;
        this.decisions = decisions;
    }

    public record StepLog(String step, String outcome) {}

    public List<StepLog> process(Long appId, CurrentUser by) {
        List<StepLog> log = new ArrayList<>();
        LoanApplication a = apps.get(appId);
        if ("DRAFT".equals(a.getAppState())) {
            apps.submit(appId, by);
            log.add(new StepLog("Submit", "Submitted"));
        }
        a = apps.get(appId);
        if (!List.of("SUBMITTED", "UNDERWRITING").contains(a.getAppState())) {
            log.add(new StepLog("Check stage", "Nothing to run at stage " + a.getAppState()));
            return log;
        }
        if (!"COMPLETE".equals(a.getDocsState())) {
            a = docs.verify(appId, by);
            log.add(new StepLog("Documents", a.getDocsState()));
        }
        if ("NOT_STARTED".equals(a.getKycState())) {
            try {
                a = data.runKyc(appId, by);
                log.add(new StepLog("KYC", a.getKycState()));
            } catch (ApiException e) {
                log.add(new StepLog("KYC", e.getMessage()));
            }
        }
        if (!"FETCHED".equals(a.getDataState())) {
            try {
                a = data.fetchData(appId, by);
                log.add(new StepLog("Bank, bureau and GST data", a.getDataState()));
            } catch (ApiException e) {
                log.add(new StepLog("Bank, bureau and GST data", e.getMessage()));
            }
        }
        a = apps.get(appId);
        List<String> blockers = decisions.blockers(a);
        if (blockers.isEmpty() && "PENDING".equals(a.getDecisionState())) {
            var d = decisions.run(appId, by);
            log.add(new StepLog("Decision engine", d.getDecision() + " · band " + d.getRiskBand() +
                    (d.getDelegationLevel() == null ? "" : " · routed to " + d.getDelegationLevel())));
        } else if (!blockers.isEmpty()) {
            log.add(new StepLog("Decision engine", "Waiting: " + String.join("; ", blockers)));
        }
        return log;
    }
}
