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
            Map.entry("RC-019", "Negative internal repayment history"));
}
