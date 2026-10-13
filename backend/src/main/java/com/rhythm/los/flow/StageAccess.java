package com.rhythm.los.flow;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Component;

/**
 * Who may act on a stage of an application: the role the product's workflow version assigns to that stage, the role
 * it was escalated to, or an admin. This is what makes "Consent & KYC: done by Sales" in the designer real.
 */
@Component
public class StageAccess {
    private final WorkflowDefinitions defs;
    private final AppStageRepository rows;

    public StageAccess(WorkflowDefinitions defs, AppStageRepository rows) {
        this.defs = defs;
        this.rows = rows;
    }

    /** The role that owns the stage in this application's workflow (the library default if the stage is not in it). */
    public String role(LoanApplication a, String key) {
        for (Stage s : defs.stages(a)) if (s.key().equals(key)) return s.role();
        return StageLibrary.def(key).role();
    }

    public boolean can(LoanApplication a, String key, CurrentUser u) {
        if (u.role() == Role.ADMIN) return true;
        String r = u.role().name();
        if (r.equals(role(a, key))) return true;
        return a.getId() != null && rows.findByApplicationIdAndStageKey(a.getId(), key).map(x -> r.equals(x.getEscalatedTo())).orElse(false);
    }

    public boolean canAny(LoanApplication a, CurrentUser u, String... keys) {
        for (String k : keys) if (can(a, k, u)) return true;
        return false;
    }

    /** Refuses with a clear message naming the role the workflow assigns. */
    public void require(LoanApplication a, CurrentUser u, String... keys) {
        if (canAny(a, u, keys)) return;
        StringBuilder who = new StringBuilder();
        for (String k : keys) {
            String r = WorkflowService.label(role(a, k));
            if (who.indexOf(r) < 0) who.append(who.length() == 0 ? "" : " or ").append(r);
        }
        throw ApiException.forbidden("NOT_YOUR_STAGE", StageLibrary.def(keys[0]).name() + " is assigned to " + who
                + " in workflow " + a.getProductCode() + (a.getWorkflowVersion() == null ? "" : " v" + a.getWorkflowVersion()));
    }
}
