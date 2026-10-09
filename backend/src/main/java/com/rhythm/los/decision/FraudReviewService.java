package com.rhythm.los.decision;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A fraud analyst clears or confirms a fraud referral. Sanction is blocked until the fraud state is CLEAR. */
@Service
public class FraudReviewService {
    private final ApplicationService apps;

    public FraudReviewService(ApplicationService apps) { this.apps = apps; }

    @Transactional
    public LoanApplication dispose(Long appId, boolean clear, String note, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!"REVIEW".equals(a.getFraudState())) throw ApiException.conflict("NOT_IN_REVIEW", "Fraud state is " + a.getFraudState());
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        apps.transition(a, Domain.FRAUD, clear ? "CLEAR" : "BLOCK", clear ? "fraud.cleared" : "fraud.confirmed", by, note);
        if (!clear) {
            if (a.getSanctionState().startsWith("PENDING_")) apps.transition(a, Domain.SANCTION, "DECLINED", "sanction.declined", by, "Fraud confirmed");
            apps.reject(a, "Fraud confirmed by analyst: " + note, by);
        }
        return a;
    }
}
