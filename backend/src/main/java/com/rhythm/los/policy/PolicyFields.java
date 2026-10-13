package com.rhythm.los.policy;

import com.rhythm.los.common.Json;

import java.util.*;

/** Describes every credit-policy parameter for the policy studio: label, group, allowed range and unit. */
public final class PolicyFields {
    private PolicyFields() {}

    public record Field(String key, String label, String group, double min, double max, double step, String unit, String help) {}

    public static final List<Field> ALL = List.of(
            new Field("minAge", "Minimum age", "Eligibility", 18, 30, 1, "years", "Applicants younger than this are declined (R01)"),
            new Field("maxAge", "Maximum age at application", "Eligibility", 50, 75, 1, "years", "Applicants older than this are declined (R01)"),
            new Field("minBankMonths", "Minimum months of bank data", "Eligibility", 3, 12, 1, "months", "Less bank history is referred (R06)"),
            new Field("kycFail", "KYC match: decline below", "KYC and fraud", 0.3, 0.8, 0.01, "share", "Identity match below this is declined (R02)"),
            new Field("kycReview", "KYC match: review below", "KYC and fraud", 0.5, 0.95, 0.01, "share", "Identity match below this goes to KYC review (R03)"),
            new Field("fraudReview", "Fraud score: refer at", "KYC and fraud", 10, 80, 1, "score", "Fraud score at or above this is referred (R05)"),
            new Field("fraudBlock", "Fraud score: decline at", "KYC and fraud", 30, 95, 1, "score", "Fraud score at or above this is declined (R04)"),
            new Field("approvePd", "Auto-approve up to PD", "Risk appetite", 0.01, 0.15, 0.005, "share", "Probability of default up to this can be auto-approved (R10)"),
            new Field("referPd", "Decline above PD", "Risk appetite", 0.05, 0.40, 0.005, "share", "Probability of default above this is declined (R09)"),
            new Field("maxEnq6", "Maximum bureau enquiries in 6 months", "Risk appetite", 2, 20, 1, "count", "More enquiries are referred (R08)"),
            new Field("dpdReject", "Decline at days past due (12 months)", "Risk appetite", 30, 180, 30, "days", "Delinquency at or above this in the last 12 months is declined (R07)"),
            new Field("maxFoir", "Maximum FOIR", "Affordability", 0.30, 0.75, 0.01, "share", "All EMIs (existing and new) as a share of sustainable income"),
            new Field("safety", "Surplus safety factor", "Affordability", 0.40, 1.00, 0.05, "share", "Share of monthly surplus that can go to the new EMI"),
            new Field("baseRate", "Base rate", "Pricing", 8, 20, 0.25, "% a year", "Cost of funds plus margin before risk loading"),
            new Field("opexRate", "Operating cost loading", "Pricing", 0, 8, 0.25, "% a year", "Added to every loan; 1.5 times for loans under ₹1 lakh"),
            new Field("maxRate", "Rate cap", "Pricing", 14, 36, 0.25, "% a year", "Highest rate the engine will price (fair-practices cap)"),
            new Field("lgdUnsecured", "Loss given default, unsecured", "Pricing", 0.30, 1.00, 0.05, "share", "Used for risk-based pricing and expected loss"),
            new Field("lgdSecured", "Loss given default, secured", "Pricing", 0.10, 0.80, 0.05, "share", "Used for risk-based pricing and expected loss"),
            new Field("exposureCap", "Maximum exposure per borrower", "Limits", 100_000, 5_000_000, 50_000, "₹", "Recommended amount never exceeds this"),
            new Field("autoApproveLimit", "Auto-approval limit", "Limits", 50_000, 2_000_000, 50_000, "₹", "Larger amounts are referred to a sanctioning officer (R13)"));

    public static Map<String, Object> asMap(PolicyParams p) {
        @SuppressWarnings("unchecked") Map<String, Object> m = Json.MAPPER.convertValue(p, LinkedHashMap.class);
        return m;
    }

    /** Range and consistency checks. Empty list = valid. */
    public static List<String> validate(PolicyParams p) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> m = asMap(p);
        for (Field f : ALL) {
            Object v = m.get(f.key());
            double d = v instanceof Number n ? n.doubleValue() : Double.NaN;
            if (Double.isNaN(d) || d < f.min() || d > f.max())
                errors.add(f.label() + " must be between " + fmt(f.min()) + " and " + fmt(f.max()));
        }
        if (p.minAge() >= p.maxAge()) errors.add("Minimum age must be below maximum age");
        if (p.kycFail() >= p.kycReview()) errors.add("KYC decline threshold must be below the review threshold");
        if (p.fraudReview() >= p.fraudBlock()) errors.add("Fraud refer score must be below the decline score");
        if (p.approvePd() >= p.referPd()) errors.add("Auto-approve PD must be below the decline PD");
        if (p.baseRate() >= p.maxRate()) errors.add("Base rate must be below the rate cap");
        if (p.lgdSecured() > p.lgdUnsecured()) errors.add("Secured LGD cannot be above unsecured LGD");
        if (p.autoApproveLimit() > p.exposureCap()) errors.add("Auto-approval limit cannot exceed the exposure cap");
        return errors;
    }

    /** Parameters whose value differs between two policies, for review screens and the audit trail. */
    public static List<Map<String, Object>> diff(PolicyParams from, PolicyParams to) {
        Map<String, Object> a = asMap(from), b = asMap(to);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Field f : ALL) {
            Object x = a.get(f.key()), y = b.get(f.key());
            if (x instanceof Number nx && y instanceof Number ny && Math.abs(nx.doubleValue() - ny.doubleValue()) > 1e-9)
                out.add(Map.of("key", f.key(), "label", f.label(), "group", f.group(), "from", nx, "to", ny, "unit", f.unit()));
        }
        return out;
    }

    static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
