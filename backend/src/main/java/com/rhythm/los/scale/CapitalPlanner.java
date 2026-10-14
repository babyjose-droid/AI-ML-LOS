package com.rhythm.los.scale;

import java.util.*;

/**
 * 12-month capital and liquidity projection (FSD 19.5): loan book, collections, cash and CRAR from the lender's
 * inputs; alerts when cash goes negative or CRAR falls below the minimum (15% for NBFCs in the middle and upper layers).
 * Monthly simple model: equal principal run-off over the average tenure, interest on the opening book, losses and
 * operating cost on the book, profit added to owned funds. Risk weight 100% unless given.
 */
public final class CapitalPlanner {
    private CapitalPlanner() {}

    public record Inputs(double ownedFunds, double borrowings, double cash, double book, double monthlyDisbursement, double disbursementGrowthPct,
                         double collectionEfficiency, double yieldPct, double costOfFundsPct, double creditLossPct, double opexPct,
                         double newBorrowingMonthly, double borrowingRepaymentMonthly, double avgTenureMonths, double minCrar, double riskWeight, int months) {}

    public record Month(int month, double disbursed, double principalCollected, double interestIncome, double interestExpense, double opex, double creditLoss,
                        double profit, double book, double borrowings, double ownedFunds, double cash, double crar, List<String> alerts) {}

    static double r0(double v) { return Math.round(v); }

    public static List<String> validate(Inputs in) {
        List<String> e = new ArrayList<>();
        if (in.ownedFunds() <= 0) e.add("Net owned funds must be positive");
        if (in.book() < 0 || in.cash() < 0 || in.borrowings() < 0) e.add("Book, cash and borrowings cannot be negative");
        if (in.collectionEfficiency() <= 0 || in.collectionEfficiency() > 1) e.add("Collection efficiency is a share between 0 and 1");
        if (in.avgTenureMonths() < 1) e.add("Average tenure must be at least 1 month");
        if (in.months() < 1 || in.months() > 36) e.add("Project 1 to 36 months");
        if (in.yieldPct() < 0 || in.yieldPct() > 60 || in.costOfFundsPct() < 0 || in.costOfFundsPct() > 30) e.add("Yield or cost of funds out of range");
        return e;
    }

    public static Map<String, Object> project(Inputs in) {
        List<Month> rows = new ArrayList<>();
        double book = in.book(), borrow = in.borrowings(), nof = in.ownedFunds(), cash = in.cash(), disb = in.monthlyDisbursement();
        double minCash = Double.MAX_VALUE, minCrar = Double.MAX_VALUE;
        int minCashMonth = 0, minCrarMonth = 0;
        List<String> alerts = new ArrayList<>();
        for (int m = 1; m <= in.months(); m++) {
            if (m > 1) disb = disb * (1 + in.disbursementGrowthPct() / 100);
            double due = book / in.avgTenureMonths();
            double principal = due * in.collectionEfficiency();
            double interest = book * in.yieldPct() / 1200 * in.collectionEfficiency();
            double loss = book * in.creditLossPct() / 1200;
            double opex = book * in.opexPct() / 1200;
            double intExp = borrow * in.costOfFundsPct() / 1200;
            double profit = interest - intExp - opex - loss;
            borrow = Math.max(0, borrow + in.newBorrowingMonthly() - in.borrowingRepaymentMonthly());
            cash = cash + principal + interest + in.newBorrowingMonthly() - in.borrowingRepaymentMonthly() - intExp - opex - disb;
            book = Math.max(0, book + disb - principal - loss);
            nof = nof + profit;
            double rwa = book * in.riskWeight();
            double crar = rwa <= 0 ? 9.99 : nof / rwa;
            List<String> a = new ArrayList<>();
            if (cash < 0) a.add("Cash negative: ₹" + Math.round(-cash) + " short");
            if (crar < in.minCrar()) a.add("CRAR " + Math.round(crar * 1000) / 10.0 + "% below the " + Math.round(in.minCrar() * 100) + "% minimum");
            if (a.size() > 0) alerts.add("Month " + m + ": " + String.join("; ", a));
            if (cash < minCash) { minCash = cash; minCashMonth = m; }
            if (crar < minCrar) { minCrar = crar; minCrarMonth = m; }
            rows.add(new Month(m, r0(disb), r0(principal), r0(interest), r0(intExp), r0(opex), r0(loss), r0(profit), r0(book), r0(borrow), r0(nof), r0(cash),
                    Math.round(crar * 10000) / 10000.0, a));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("months", rows);
        out.put("minCash", r0(minCash));
        out.put("minCashMonth", minCashMonth);
        out.put("minCrar", Math.round(minCrar * 10000) / 10000.0);
        out.put("minCrarMonth", minCrarMonth);
        out.put("alerts", alerts);
        out.put("endBook", rows.isEmpty() ? 0 : rows.get(rows.size() - 1).book());
        out.put("totalDisbursed", r0(rows.stream().mapToDouble(Month::disbursed).sum()));
        out.put("totalProfit", r0(rows.stream().mapToDouble(Month::profit).sum()));
        return out;
    }
}
