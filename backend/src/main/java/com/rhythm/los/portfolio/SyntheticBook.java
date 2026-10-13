package com.rhythm.los.portfolio;

import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a plausible synthetic loan book with 12-month outcomes, so the policy studio and model training can be
 * demonstrated before the NBFC's own history is imported. The outcome comes from a hidden "true" risk function that is
 * deliberately different from the engine's illustrative coefficients, so training a model on it is meaningful.
 *
 * Every loan is labelled SYNTHETIC. It has no selection bias (all applicants are "funded"), unlike a real book.
 */
public final class SyntheticBook {
    private SyntheticBook() {}

    public record Loan(EngineInput input, String product, boolean bad, LocalDate disbursedOn) {}

    public static List<Loan> generate(int n, long seed) {
        Random r = new Random(seed);
        List<Loan> out = new ArrayList<>(n);
        LocalDate start = LocalDate.of(2024, 4, 1);
        for (int i = 0; i < n; i++) {
            double u = r.nextDouble();
            String seg = u < 0.45 ? "SALARIED" : u < 0.85 ? "MSME" : "MICROFINANCE";
            String product = switch (seg) { case "SALARIED" -> "PL"; case "MSME" -> "BL-UNS"; default -> "JLG"; };
            int age = 22 + r.nextInt(38);
            double vintage = round1(switch (seg) { case "SALARIED" -> 0.5 + r.nextDouble() * 14; case "MSME" -> 1 + r.nextDouble() * 19; default -> 1 + r.nextDouble() * 14; });
            double median = switch (seg) { case "SALARIED" -> 45000; case "MSME" -> 60000; default -> 18000; };
            double income = Math.round(median * Math.exp(r.nextGaussian() * 0.45) / 100) * 100.0;
            double cv = switch (seg) { case "SALARIED" -> 0.05; case "MSME" -> 0.2; default -> 0.25; } * (0.5 + r.nextDouble());
            int months = r.nextDouble() < 0.85 ? 12 : 6 + r.nextInt(6);
            List<Double> in = new ArrayList<>(), outF = new ArrayList<>();
            int negMonths = 0;
            for (int m = 0; m < months; m++) {
                double x = Math.max(income * 0.2, income * (1 + r.nextGaussian() * cv));
                double y = x * (0.75 + r.nextDouble() * 0.29);
                if (y > x) negMonths++;
                in.add((double) Math.round(x));
                outF.add((double) Math.round(y));
            }
            double avgBal = Math.round(income * (0.05 + r.nextDouble() * 1.1));
            int bounces = r.nextDouble() < 0.83 ? 0 : r.nextDouble() < 0.7 ? 1 : 2 + r.nextInt(3);

            double hitP = switch (seg) { case "SALARIED" -> 0.9; case "MSME" -> 0.75; default -> 0.35; };
            EngineInput.Bureau bureau = null;
            double existingEmi;
            int score = 0, dpd = 0;
            if (r.nextDouble() < hitP) {
                score = (int) Math.max(560, Math.min(860, 725 + r.nextGaussian() * 55));
                double d = r.nextDouble();
                dpd = d < 0.80 ? 0 : d < 0.92 ? 30 : d < 0.97 ? 60 : 90;
                boolean ever90 = dpd >= 90 || r.nextDouble() < 0.02;
                existingEmi = Math.round(income * r.nextDouble() * 0.35);
                bureau = new EngineInput.Bureau(score, r.nextInt(7), dpd, ever90, r.nextInt(9), 6 + r.nextInt(175), existingEmi);
            } else {
                existingEmi = Math.round(income * r.nextDouble() * 0.15);
            }
            double detectedEmi = Math.round(existingEmi * (0.8 + r.nextDouble() * 0.3));
            EngineInput.Gst gst = "MSME".equals(seg) && r.nextDouble() < 0.7
                    ? new EngineInput.Gst(round2(0.5 + r.nextDouble() * 0.5), round2(0.05 + r.nextGaussian() * 0.15), round2(r.nextDouble() * 0.4)) : null;
            EngineInput.Internal internal = "MICROFINANCE".equals(seg) && r.nextBoolean()
                    ? new EngineInput.Internal(1 + r.nextInt(4), r.nextDouble() < 0.1 ? 45 : 0) : null;
            boolean weakKyc = r.nextDouble() < 0.05;
            EngineInput.Kyc kyc = new EngineInput.Kyc(round2(weakKyc ? 0.6 + r.nextDouble() * 0.2 : 0.88 + r.nextDouble() * 0.12), 1,
                    1, round2(weakKyc ? 0.5 : 0.8 + r.nextDouble() * 0.2), round2(0.7 + r.nextDouble() * 0.3));
            int dup = r.nextDouble() < 0.95 ? 0 : 1 + r.nextInt(3);
            boolean tamper = r.nextDouble() < 0.01;
            int velocity = r.nextDouble() < 0.9 ? 1 : 2 + r.nextInt(3);
            double inflate = r.nextDouble() < 0.8 ? 1 + r.nextDouble() * 0.1 : 1.1 + r.nextDouble() * 0.5;
            double declared = Math.round(income * inflate / 100) * 100.0;
            double essential = Math.round(income * (0.2 + r.nextDouble() * 0.25) / 100) * 100.0;
            double amount = switch (seg) {
                case "SALARIED" -> 50_000 + r.nextInt(46) * 10_000;
                case "MSME" -> 50_000 + r.nextInt(96) * 10_000;
                default -> 10_000 + r.nextInt(19) * 5_000;
            };
            int[] tenures = "MICROFINANCE".equals(seg) ? new int[]{12, 18, 24} : new int[]{12, 18, 24, 36, 48};
            int tenure = tenures[r.nextInt(tenures.length)];
            boolean jlg = "MICROFINANCE".equals(seg);
            EngineInput.Bank bank = new EngineInput.Bank(in, outF, avgBal, bounces, detectedEmi);
            EngineInput input = new EngineInput("HIST-" + (i + 1), "Synthetic borrower " + (i + 1), seg, age, vintage, kyc, declared, essential,
                    bank, bureau, gst, internal, jlg, false, new EngineInput.Fraud(dup, tamper, velocity), new EngineInput.Loan(amount, tenure));

            // hidden "true" risk: depends on the same signals, with different weights, interactions and noise
            double emiNew = amount / tenure * 1.12;
            double foir = (existingEmi + emiNew) / income;
            double z = -3.9
                    + (bureau != null ? 0.011 * (690 - score) : 0.55)
                    + 1.8 * Math.max(0, foir - 0.35)
                    + 0.5 * bounces
                    + 2.4 * cv
                    + 0.012 * dpd
                    + 0.15 * negMonths
                    + 0.9 * Math.max(0, inflate - 1.25)
                    - 0.05 * Math.min(vintage, 10)
                    - 0.6 * Math.min(avgBal / income, 1)
                    + (gst != null ? 0.8 * (0.85 - gst.filing()) : 0)
                    + (internal != null ? (internal.maxDpd() > 30 ? 1.0 : -0.6) : 0)
                    + (tamper ? 1.5 : 0) + 0.4 * dup
                    + (age < 25 ? 0.25 : 0)
                    + r.nextGaussian() * 0.35;
            boolean bad = r.nextDouble() < 1 / (1 + Math.exp(-z));
            out.add(new Loan(input, product, bad, start.plusDays(r.nextInt(365))));
        }
        return out;
    }

    public static List<HistoricalLoan> entities(int n, long seed) {
        List<HistoricalLoan> out = new ArrayList<>();
        for (Loan l : generate(n, seed)) {
            out.add(new HistoricalLoan(l.input().id(), "SYNTHETIC", l.input().segment(), l.product(), BigDecimal.valueOf(l.input().loan().amount()),
                    l.disbursedOn(), Json.write(l.input()), l.bad()));
        }
        return out;
    }

    private static double round1(double x) { return Math.round(x * 10) / 10.0; }
    private static double round2(double x) { return Math.round(x * 100) / 100.0; }
}
