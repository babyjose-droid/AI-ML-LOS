package com.rhythm.los.sanction;

import java.util.List;

/** Key Fact Statement shown to the borrower before acceptance (RBI digital lending directions). */
public record Kfs(
        String applicationNo,
        String borrower,
        String product,
        double sanctionedAmount,
        double processingFee,
        double gstOnFee,
        double insurance,
        double netDisbursed,
        double ratePa,
        double aprPa,
        int tenureMonths,
        double emi,
        double totalInterest,
        double totalPayable,
        int coolingOffDays,
        String repaymentMode,
        String grievanceOfficer,
        List<Row> schedule,
        List<Charge> contingentCharges,
        List<Party> parties) {

    /** A co-applicant or guarantor named on the KFS (RBI: every borrower and guarantor sees the same terms). */
    public record Party(String role, String name, String relationship) {}

    public Kfs withParties(List<Party> p) {
        return new Kfs(applicationNo, borrower, product, sanctionedAmount, processingFee, gstOnFee, insurance, netDisbursed, ratePa, aprPa,
                tenureMonths, emi, totalInterest, totalPayable, coolingOffDays, repaymentMode, grievanceOfficer, schedule, contingentCharges, p == null ? List.of() : List.copyOf(p));
    }

    public record Row(int month, double emi, double interest, double principal, double balance) {}
    /** Charges that apply only if something happens (foreclosure, a bounced EMI, overdue): shown in the KFS, not in the APR. */
    public record Charge(String name, String amount) {}
}
