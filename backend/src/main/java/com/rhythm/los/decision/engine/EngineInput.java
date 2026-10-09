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
    public record Bank(List<Double> inflows, List<Double> outflows, double avgBalance, int bounces, double detectedEmi) {}
    public record Bureau(int score, int active, int maxDpd12, boolean ever90, int enq6, int ageM, double emi) {}
    public record Gst(double filing, double growth, double gap) {}
    public record Internal(int prevLoans, int maxDpd) {}
    public record Fraud(int dupLinks, boolean docTamper, int velocity) {}
    public record Loan(double amount, int tenure) {}
}
