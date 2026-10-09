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
        List<Row> schedule) {

    public record Row(int month, double emi, double interest, double principal, double balance) {}
}
