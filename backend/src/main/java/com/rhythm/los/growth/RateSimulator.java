package com.rhythm.los.growth;

import java.util.ArrayList;
import java.util.List;

/**
 * What a change in lending rates does to new business: volume (price elasticity), ticket size (affordability: a
 * higher EMI fits a smaller loan), adverse selection (better borrowers leave first when rates rise), yield, NIM,
 * expected loss and risk-adjusted return. The mix is the current book; rates are capped at the policy maximum.
 */
public final class RateSimulator {
    private RateSimulator() {}

    public record Loan(double amount, double rate, double pd, double lgd, int tenureMonths) {}
    public record Assumptions(double elasticityPerPp, double adverseSelectionPerPp, double costOfFunds, double opexRate, double maxRate) {}
    public record Outcome(double changePp, int loans, double volume, double yield, double nim, double expectedLossRate, double riskAdjustedReturn,
                          double annualNetIncome, int capped) {}

    static double emi(double p, double rate, int n) {
        double r = rate / 1200;
        return r == 0 ? p / n : p * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
    }

    public static Outcome run(List<Loan> mix, double changePp, Assumptions a) {
        double vol = 0, rateW = 0, elW = 0, takeTotal = 0;
        int capped = 0;
        for (Loan l : mix) {
            double r2 = l.rate() + changePp;
            if (r2 > a.maxRate()) { r2 = a.maxRate(); capped++; }
            r2 = Math.max(r2, a.costOfFunds() + a.opexRate());
            double dp = r2 - l.rate();
            double takeUp = Math.max(0, Math.min(1.5, 1 - a.elasticityPerPp() / 100 * dp));
            // half of borrowers borrow to their EMI limit: a higher rate means a smaller loan for them
            double ratio = emi(l.amount(), l.rate(), l.tenureMonths()) / emi(l.amount(), r2, l.tenureMonths());
            double amount = l.amount() * (0.5 + 0.5 * ratio);
            double pd = Math.min(1, l.pd() * Math.max(0.5, 1 + a.adverseSelectionPerPp() / 100 * dp));
            double w = takeUp * amount;
            takeTotal += takeUp;
            vol += w;
            rateW += w * r2;
            elW += w * pd * l.lgd() * 100;
        }
        double yield = vol == 0 ? 0 : rateW / vol, el = vol == 0 ? 0 : elW / vol;
        double nim = yield - a.costOfFunds();
        double rar = nim - el - a.opexRate();
        return new Outcome(changePp, (int) Math.round(takeTotal), Math.round(vol), r2(yield), r2(nim), r2(el), r2(rar), Math.round(vol * rar / 100), capped);
    }

    public static List<Outcome> curve(List<Loan> mix, Assumptions a) {
        List<Outcome> out = new ArrayList<>();
        for (int bps = -300; bps <= 300; bps += 50) out.add(run(mix, bps / 100.0, a));
        return out;
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }
}
