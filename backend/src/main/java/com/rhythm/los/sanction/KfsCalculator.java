package com.rhythm.los.sanction;

import com.rhythm.los.decision.engine.Finance;

import java.util.ArrayList;
import java.util.List;

public final class KfsCalculator {
    private KfsCalculator() {}

    public static Kfs build(String appNo, String borrower, String product, double amount, double ratePa, int months, double feePct) {
        double fee = Finance.round(amount * feePct / 100);
        double gst = Finance.round(fee * 0.18);
        double insurance = 0;
        double net = amount - fee - gst - insurance;
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
        return new Kfs(appNo, borrower, product, amount, fee, gst, insurance, net, ratePa, apr, months, emi,
                totalInterest, totalPayable, 3, "eNACH (monthly)", "Grievance Redressal Officer, grievance@example-nbfc.in", rows);
    }

    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
