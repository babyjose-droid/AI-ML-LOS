package com.rhythm.los.decision.engine;

/** Loan maths shared by the engine and the KFS. Rates are annual percentages. */
public final class Finance {
    private Finance() {}

    public static double emi(double principal, double annualRate, int months) {
        double r = annualRate / 1200;
        if (r == 0) return principal / months;
        double f = Math.pow(1 + r, months);
        return principal * r * f / (f - 1);
    }

    public static double pv(double emi, double annualRate, int months) {
        double r = annualRate / 1200;
        if (r == 0) return emi * months;
        double f = Math.pow(1 + r, months);
        return emi * (f - 1) / (r * f);
    }

    /** Annualised percentage rate: the monthly rate at which the EMI stream discounts to the net amount received. */
    public static double apr(double netDisbursed, double emi, int months) {
        double lo = 0, hi = 0.2;
        for (int k = 0; k < 100; k++) {
            double m = (lo + hi) / 2;
            double v = emi * (1 - Math.pow(1 + m, -months)) / m;
            if (v > netDisbursed) lo = m; else hi = m;
        }
        return (lo + hi) / 2 * 1200;
    }

    /** JavaScript-compatible Math.round for non-negative and negative values. */
    public static long round(double x) {
        return (long) Math.floor(x + 0.5);
    }
}
