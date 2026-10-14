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
        Loan loan,
        Eligibility eligibility,
        Household household,
        Joint joint) {

    /** Without co-applicants or guarantors. */
    public EngineInput(String id, String name, String segment, int age, double vintage, Kyc kyc, double declaredIncome, double essential,
                       Bank bank, Bureau bureau, Gst gst, Internal internal, boolean jlg, boolean secured, Fraud fraud, Loan loan,
                       Eligibility eligibility, Household household) {
        this(id, name, segment, age, vintage, kyc, declaredIncome, essential, bank, bureau, gst, internal, jlg, secured, fraud, loan, eligibility, household, null);
    }

    /** Without household data (most products). */
    public EngineInput(String id, String name, String segment, int age, double vintage, Kyc kyc, double declaredIncome, double essential,
                       Bank bank, Bureau bureau, Gst gst, Internal internal, boolean jlg, boolean secured, Fraud fraud, Loan loan,
                       Eligibility eligibility) {
        this(id, name, segment, age, vintage, kyc, declaredIncome, essential, bank, bureau, gst, internal, jlg, secured, fraud, loan, eligibility, null);
    }

    /**
     * Household cash flow (FSD 13.4, RBI microfinance directions 2022): monthly income of the members included,
     * the household's essential expenses and its existing loan repayments.
     */
    public record Household(int members, int earners, double monthlyIncome, double monthlyExpenses, double existingEmi) {}

    /**
     * Co-applicants and guarantors: income of the co-applicants the product lets count (clubbedIncome) and their own
     * loan repayments (clubbedEmi) join the applicant's for affordability; policyFails are the party policy rules not met
     * (bureau score, age at maturity, guarantees already given), which refer the case.
     */
    public record Joint(int coApplicants, int guarantors, int earnersCounted, double clubbedIncome, double clubbedEmi, List<String> policyFails) {}

    /**
     * The product's eligibility and price band (FSD 14.1 step 3 and step 9). Zero limits mean "no limit set"; maxAge is the
     * age at loan maturity. served is whether a branch offering the product serves the applicant's PIN code (null = not checked).
     */
    public record Eligibility(String product, int minAge, int maxAge, double minVintage, double minIncome, Boolean served,
                              double rateMin, double rateMax) {}

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
    /** rotationShare: share of statement credits that rotate straight back out; networkScore: 0-100 from the application network. */
    public record Fraud(int dupLinks, boolean docTamper, int velocity, double rotationShare, int networkScore) {
        public Fraud(int dupLinks, boolean docTamper, int velocity) { this(dupLinks, docTamper, velocity, 0, 0); }
    }
    public record Loan(double amount, int tenure) {}
}
