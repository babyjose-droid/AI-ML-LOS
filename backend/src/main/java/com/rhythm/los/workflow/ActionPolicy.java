package com.rhythm.los.workflow;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.rhythm.los.org.Role.*;

/** Which actions the current user can take on an application right now. Drives the buttons in the UI. */
public final class ActionPolicy {
    private ActionPolicy() {}

    public static List<String> actions(LoanApplication a, CurrentUser u) {
        Role r = u.role();
        List<String> out = new ArrayList<>();
        boolean front = is(r, SALES, OPERATIONS, ADMIN);
        boolean ops = is(r, OPERATIONS, ADMIN);
        String app = a.getAppState();
        boolean active = Set.of("SUBMITTED", "UNDERWRITING").contains(app);

        if ("DRAFT".equals(app) && front) { out.add("edit"); out.add("submit"); out.add("withdraw"); }
        if ("SUBMITTED".equals(app) && front) out.add("withdraw");
        if (Set.of("DRAFT", "SUBMITTED", "UNDERWRITING").contains(app) && front) out.add("uploadDocument");
        if (active && ops) {
            out.add("process");
            if ("NOT_STARTED".equals(a.getKycState())) out.add("runKyc");
            if (!"FETCHED".equals(a.getDataState())) out.add("fetchData");
            if (!"COMPLETE".equals(a.getDocsState())) out.add("verifyDocuments");
            if (!a.getSanctionState().equals("SANCTIONED")) out.add("runDecision");
        }
        if (active && "REVIEW".equals(a.getKycState()) && ops) out.add("resolveKyc");
        if (ApplicationService.isOpen(a) && "IN_REVIEW".equals(a.getDocsState()) && ops) out.add("reviewDocuments");
        if (active && "REQUIRED".equals(a.getFieldState()) && is(r, OPERATIONS, CREDIT_OFFICER, ADMIN)) out.add("fieldVisit");
        if ("REVIEW".equals(a.getFraudState()) && is(r, FRAUD_ANALYST)) out.add("fraudDisposition");
        if (a.getSanctionState().startsWith("PENDING_")) {
            String lvl = a.getSanctionState().substring(8);
            if (r.sanctionLevel() >= Delegation.rank(lvl)) {
                out.add("sanction");
                out.add("decline");
                if (!"L3".equals(lvl)) out.add("escalate");
            }
        }
        if ("SANCTIONED".equals(a.getSanctionState()) && front) out.add("acceptKfs");
        if ("READY".equals(a.getDisbState()) && ops) out.add("disburse");
        if ("FAILED".equals(a.getDisbState()) && ops) out.add("retryDisbursement");
        return out;
    }

    private static boolean is(Role r, Role... any) {
        for (Role x : any) if (x == r) return true;
        return false;
    }
}
