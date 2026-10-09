package com.rhythm.los.policy;

/** Credit policy thresholds used by the decision engine. Stored as JSON per policy version. */
public record PolicyParams(
        String version,
        int minAge, int maxAge,
        double kycFail, double kycReview,
        double fraudReview, double fraudBlock,
        double maxFoir, double safety,
        double approvePd, double referPd,
        int maxEnq6, int dpdReject, int minBankMonths,
        double baseRate, double opexRate, double maxRate,
        double lgdUnsecured, double lgdSecured,
        double exposureCap, double autoApproveLimit) {

    public static PolicyParams defaults() {
        return new PolicyParams("POL-2026.10-v4", 21, 65, 0.60, 0.80, 35, 70, 0.50, 0.70, 0.07, 0.15,
                6, 90, 6, 13.0, 3.0, 26.0, 0.65, 0.35, 1_500_000, 500_000);
    }
}
