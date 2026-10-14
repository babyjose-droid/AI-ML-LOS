package com.rhythm.los.decision.engine;

import java.util.Map;

public final class ReasonCodes {
    private ReasonCodes() {}

    public static final Map<String, String> TEXT = Map.ofEntries(
            Map.entry("RC-001", "High probability of default"),
            Map.entry("RC-002", "High existing debt obligation"),
            Map.entry("RC-003", "FOIR above policy threshold"),
            Map.entry("RC-004", "Income insufficient / unstable"),
            Map.entry("RC-005", "Recent delinquency"),
            Map.entry("RC-006", "High credit enquiry velocity"),
            Map.entry("RC-008", "Short credit history"),
            Map.entry("RC-009", "Cash-flow stress"),
            Map.entry("RC-010", "Document inconsistency"),
            Map.entry("RC-011", "Identity/KYC mismatch"),
            Map.entry("RC-012", "Fraud risk above review threshold"),
            Map.entry("RC-013", "Application data incomplete"),
            Map.entry("RC-014", "Policy exception requires human approval"),
            Map.entry("RC-015", "External data unavailable (bureau thin-file)"),
            Map.entry("RC-016", "Exposure limit exceeded"),
            Map.entry("RC-017", "Business cash-flow volatility"),
            Map.entry("RC-007", "High credit utilisation"),
            Map.entry("RC-018", "Recent borrowing increase"),
            Map.entry("RC-019", "Negative internal repayment history"),
            Map.entry("RC-020", "Model or data quality error"),
            // lender-specific extensions beyond the FSD's standard list (RC-001 to RC-020)
            Map.entry("RC-021", "Betting / gambling transactions in bank statement"),
            Map.entry("RC-022", "Not eligible for this product"),
            Map.entry("RC-023", "Money rotation in the bank statement"),
            Map.entry("RC-024", "Linked to risky applications (network)"),
            Map.entry("RC-025", "Household repayments above the microfinance cap"),
            Map.entry("RC-026", "Co-applicant or guarantor does not meet policy"));
}
