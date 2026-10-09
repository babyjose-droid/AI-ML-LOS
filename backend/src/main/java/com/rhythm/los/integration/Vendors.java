package com.rhythm.los.integration;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.integration.VendorModels.*;

/**
 * Vendor ports. Phase 1 ships mock implementations (MockVendors); real adapters for
 * Signzy/IDfy/HyperVerge, Setu/FinBox, CIBIL/CRIF, Digio/Leegality and Cashfree/RazorpayX
 * implement the same interfaces and are switched on by configuration.
 */
public final class Vendors {
    private Vendors() {}

    public interface KycProvider {
        String name();
        KycBundle verify(LoanApplication app);
        /** True when KYC is derived from verified documents, so documents must be complete first. */
        default boolean needsVerifiedDocuments() { return false; }
    }
    public interface AccountAggregator { String name(); AaStatement fetchStatements(LoanApplication app, int months); }
    public interface CreditBureau { String name(); BureauReport pull(LoanApplication app); }
    public interface GstProvider { String name(); GstProfile profile(LoanApplication app); }
    public interface BankVerification { String name(); PennyDropResult pennyDrop(LoanApplication app); }
    public interface PayoutProvider { String name(); PayoutResult pay(LoanApplication app, double amount, String reference); }
}
