package com.rhythm.los.sanction;

import com.rhythm.los.decision.engine.Finance;

import java.util.ArrayList;
import java.util.List;

public final class KfsCalculator {
    private KfsCalculator() {}

    /** The product's fees and charges that flow into the KFS and APR (FSD 20.5). */
    public record Charges(double feePct, double minFee, double foreclosurePct, double bounceCharge, double penalRatePct, String frequency) {
        public static Charges of(double feePct) { return new Charges(feePct, 0, 0, 0, 0, "MONTHLY"); }
    }

    public static Kfs build(String appNo, String borrower, String product, double amount, double ratePa, int months, double feePct) {
        return build(appNo, borrower, product, amount, ratePa, months, Charges.of(feePct));
    }

    public static Kfs build(String appNo, String borrower, String product, double amount, double ratePa, int months, Charges c) {
        double fee = Finance.round(Math.max(amount * c.feePct() / 100, c.minFee()));
        double gst = Finance.round(fee * 0.18);
        double insurance = 0;
        double net = amount - fee - gst - insurance;
        if ("REVOLVING".equals(c.frequency())) return revolving(appNo, borrower, product, amount, ratePa, months, c, fee, gst, net);
        double emi = Finance.round(Finance.emi(amount, ratePa, months));
        double apr = Math.round(Finance.apr(net, emi, months) * 100) / 100.0;
        List<Kfs.Row> rows = new ArrayList<>();
        double bal = amount, r = ratePa / 1200;
        for (int k = 1; k <= months; k++) {
            double interest = round2(bal * r);
            double principal = k == months ? round2(bal) : round2(emi - interest);
            double pay = k == months ? round2(principal + interest) : emi;
            bal = Math.max(0, round2(bal - principal));
            rows.add(new Kfs.Row(k, pay, interest, principal, bal));
        }
        double totalInterest = round2(rows.stream().mapToDouble(Kfs.Row::interest).sum());
        double totalPayable = round2(rows.stream().mapToDouble(Kfs.Row::emi).sum() + fee + gst + insurance);
        List<Kfs.Charge> contingent = new ArrayList<>();
        if (c.foreclosurePct() > 0) contingent.add(new Kfs.Charge("Foreclosure charge", c.foreclosurePct() + "% of principal outstanding + GST"));
        if (c.bounceCharge() > 0) contingent.add(new Kfs.Charge("Bounce charge", "₹" + Math.round(c.bounceCharge()) + " per bounced instalment + GST"));
        if (c.penalRatePct() > 0) contingent.add(new Kfs.Charge("Penal charge", c.penalRatePct() + "% p.a. on the overdue amount (not added to the interest rate)"));
        String freq = c.frequency() == null ? "monthly" : c.frequency().toLowerCase();
        return new Kfs(appNo, borrower, product, amount, fee, gst, insurance, net, ratePa, apr, months, emi,
                totalInterest, totalPayable, 3, "eNACH (" + freq + ")", "Grievance Redressal Officer, grievance@example-nbfc.in", rows, contingent, java.util.List.of());
    }

    /**
     * A revolving working-capital limit (trade finance): illustrated fully drawn for the validity, interest paid monthly and the
     * principal on expiry (in practice each drawdown is repaid on its invoice's due date). APR from those cash flows.
     */
    static Kfs revolving(String appNo, String borrower, String product, double amount, double ratePa, int months, Charges c, double fee, double gst, double net) {
        double r = ratePa / 1200, interest = round2(amount * r);
        List<Kfs.Row> rows = new ArrayList<>();
        for (int k = 1; k <= months; k++) rows.add(new Kfs.Row(k, k == months ? round2(amount + interest) : interest, interest, k == months ? amount : 0, k == months ? 0 : amount));
        double lo = 0, hi = 2;                          // monthly IRR of: receive net, pay the rows
        for (int i = 0; i < 100; i++) {
            double mid = (lo + hi) / 2, pv = 0;
            for (Kfs.Row row : rows) pv += row.emi() / Math.pow(1 + mid, row.month());
            if (pv > net) lo = mid; else hi = mid;
        }
        double apr = Math.round(lo * 1200 * 100) / 100.0;
        double totalInterest = round2(interest * months);
        List<Kfs.Charge> contingent = new ArrayList<>();
        contingent.add(new Kfs.Charge("How it works", "Draw against eligible invoices up to the limit; each drawdown is repaid on the invoice's due date and the limit is available again"));
        if (c.penalRatePct() > 0) contingent.add(new Kfs.Charge("Penal charge", c.penalRatePct() + "% p.a. on a drawdown overdue after its due date (not added to the interest rate)"));
        if (c.bounceCharge() > 0) contingent.add(new Kfs.Charge("Bounce charge", "₹" + Math.round(c.bounceCharge()) + " per dishonoured repayment + GST"));
        return new Kfs(appNo, borrower, product, amount, fee, gst, 0, net, ratePa, apr, months, interest,
                totalInterest, round2(totalInterest + amount + fee + gst), 3, "Revolving limit: interest monthly, principal on each invoice's due date",
                "Grievance Redressal Officer, grievance@example-nbfc.in", rows, contingent, java.util.List.of());
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
