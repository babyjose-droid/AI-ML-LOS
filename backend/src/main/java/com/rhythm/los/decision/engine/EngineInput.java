package com.rhythm.los.decision.engine;

import java.util.List;

/** Everything the decision engine needs, assembled from verified data snapshots. Nullable parts mean "not available". */
public record EngineInput(
        String id,
        String name,
        String segment,
        int age,
        double vintage,
        Kyc kyc,
        double declaredIncome,
        double essential,
        Bank bank,
        Bureau bureau,
        Gst gst,
        Internal internal,
        boolean jlg,
        boolean secured,
        Fraud fraud,
        Loan loan) {

    public record Kyc(double name, double dob, double id, double address, double contact) {}
    /**
     * Bank cash flow. source is where it came from ("AA" or "bank statement"); employerMismatch is true when the
     * salary credits in the statement come from a different employer than the one on the salary slip.
     */
    public record Bank(List<Double> inflows, List<Double> outflows, double avgBalance, int bounces, double detectedEmi, int gambling,
                       String source, boolean employerMismatch) {
        public Bank(List<Double> inflows, List<Double> outflows, double avgBalance, int bounces, double detectedEmi) {
            this(inflows, outflows, avgBalance, bounces, detectedEmi, 0, "AA", false);
        }
        public Bank(List<Double> inflows, List<Double> outflows, double avgBalance, int bounces, double detectedEmi, int gambling) {
            this(inflows, outflows, avgBalance, bounces, detectedEmi, gambling, "AA", false);
        }
    }
    public record Bureau(int score, int active, int maxDpd12, boolean ever90, int enq6, int ageM, double emi) {}
    public record Gst(double filing, double growth, double gap) {}
    public record Internal(int prevLoans, int maxDpd) {}
    public record Fraud(int dupLinks, boolean docTamper, int velocity) {}
    public record Loan(double amount, int tenure) {}
}
