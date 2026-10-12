package com.rhythm.los.integration;

import java.util.List;

/** Normalised vendor responses. Real adapters map provider payloads into these. */
public final class VendorModels {
    private VendorModels() {}

    public record PanResult(String pan, String registeredName, String status, String dob) {}
    public record AadhaarResult(String maskedAadhaar, String name, String dob, String address, double addressMatch) {}
    public record CkycResult(boolean found, String ckycNo, double contactMatch) {}
    public record KycBundle(PanResult pan, AadhaarResult aadhaar, CkycResult ckyc,
                            double nameMatch, double dobMatch, double idMatch, double addressMatch, double contactMatch) {}

    /** Bank data for underwriting: from the Account Aggregator, or from an AI-read bank statement (gambling is then known). */
    public record AaStatement(String fipName, String maskedAccount, int months, List<Double> inflows, List<Double> outflows,
                              double avgBalance, int bounces, double detectedEmi, int gambling) {
        public AaStatement(String fipName, String maskedAccount, int months, List<Double> inflows, List<Double> outflows,
                           double avgBalance, int bounces, double detectedEmi) {
            this(fipName, maskedAccount, months, inflows, outflows, avgBalance, bounces, detectedEmi, 0);
        }
    }

    public record BureauReport(String bureau, boolean hit, int score, int activeLines, int maxDpd12, boolean ever90,
                               int enquiries6m, int historyMonths, double totalEmi) {}

    public record GstProfile(String gstin, double filingRegularity, double turnoverGrowth, double bankGstGap) {}

    public record PennyDropResult(boolean accountValid, String beneficiaryName, double nameMatch, String reference) {}

    public record PayoutResult(boolean success, String utr, String failureReason) {}
}
