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

    public record AaStatement(String fipName, String maskedAccount, int months, List<Double> inflows, List<Double> outflows,
                              double avgBalance, int bounces, double detectedEmi) {}

    public record BureauReport(String bureau, boolean hit, int score, int activeLines, int maxDpd12, boolean ever90,
                               int enquiries6m, int historyMonths, double totalEmi) {}

    public record GstProfile(String gstin, double filingRegularity, double turnoverGrowth, double bankGstGap) {}

    public record PennyDropResult(boolean accountValid, String beneficiaryName, double nameMatch, String reference) {}

    public record PayoutResult(boolean success, String utr, String failureReason) {}
}
