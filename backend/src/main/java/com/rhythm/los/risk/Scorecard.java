package com.rhythm.los.risk;

import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;

import java.util.*;

/**
 * Industry scorecards (FSD 14.3): a points table per industry, built from what a credit officer already knows about the
 * trade (stock against the loan, animals and milk-society membership for dairy, vehicle age for transport). Points are
 * scaled so that base points mean odds of {@code baseOdds} to one of being good, and every {@code pdo} points double
 * the odds. Used as a challenger next to the PD model; a characteristic with no data scores neutral and is listed.
 */
public final class Scorecard {
    private Scorecard() {}

    public record Bin(Double max, int points) {}
    public record Characteristic(String key, String label, List<Bin> bins) {}
    public record Definition(String code, String name, String industry, int basePoints, double baseOdds, double pdo, List<Characteristic> characteristics) {}
    public record Line(String key, String label, Double value, int points) {}
    public record Result(String code, String name, int score, double pd, String band, List<Line> lines, List<String> missing) {}

    /** The characteristics the scorecards can use, from the engine's input and result and from field-visit data. */
    public static Map<String, Double> values(EngineInput in, EngineResult r, Map<String, Double> field) {
        Map<String, Double> v = new HashMap<>();
        v.put("vintage", in.vintage());
        v.put("age", (double) in.age());
        v.put("stability", r.stability());
        v.put("foirPost", r.foirPost());
        v.put("bounces", (double) in.bank().bounces());
        v.put("balanceToIncome", r.income() > 0 ? in.bank().avgBalance() / r.income() : 0);
        v.put("negativeMonths", (double) r.negMonths());
        if (in.bureau() != null) v.put("bureauScore", (double) in.bureau().score());
        if (in.gst() != null) v.put("gstFiling", in.gst().filing());
        if (in.fraud() != null) v.put("rotationShare", in.fraud().rotationShare());
        if (in.household() != null && in.household().monthlyIncome() > 0)
            v.put("householdSurplusRatio", (in.household().monthlyIncome() - in.household().monthlyExpenses() - in.household().existingEmi()) / in.household().monthlyIncome());
        if (field != null) {
            field.forEach((k, x) -> { if (x != null) v.put(k, x); });
            if (field.get("stockValue") != null && in.loan().amount() > 0) v.put("stockToLoan", field.get("stockValue") / in.loan().amount());
        }
        return v;
    }

    public static Result score(Definition d, Map<String, Double> values) {
        int total = d.basePoints();
        List<Line> lines = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Characteristic c : d.characteristics()) {
            Double x = values.get(c.key());
            if (x == null) { missing.add(c.label()); lines.add(new Line(c.key(), c.label(), null, 0)); continue; }
            int pts = 0;
            for (Bin b : c.bins()) if (b.max() == null || x <= b.max()) { pts = b.points(); break; }
            total += pts;
            lines.add(new Line(c.key(), c.label(), Math.round(x * 1000) / 1000.0, pts));
        }
        // odds(good:bad) = baseOdds * 2^((score - base)/pdo); PD = 1 / (1 + odds)
        double odds = d.baseOdds() * Math.pow(2, (total - d.basePoints()) / d.pdo());
        double pd = 1 / (1 + odds);
        return new Result(d.code(), d.name(), total, Math.round(pd * 10000) / 10000.0, UnderwritingEngine.band(pd), lines, missing);
    }

    static Bin b(Double max, int pts) { return new Bin(max, pts); }
    static Characteristic c(String key, String label, Bin... bins) { return new Characteristic(key, label, List.of(bins)); }

    /** Built-in illustrative scorecards; the lender recalibrates the points on its own book. */
    public static List<Definition> builtIns() {
        Characteristic vintage = c("vintage", "Years in business", b(1d, -35), b(3d, -10), b(7d, 10), b(null, 25));
        Characteristic stability = c("stability", "Income stability", b(0.4, -40), b(0.6, -10), b(0.8, 10), b(null, 25));
        Characteristic bounces = c("bounces", "Bounces (12m)", b(0d, 20), b(1d, -5), b(3d, -30), b(null, -60));
        Characteristic foir = c("foirPost", "FOIR after the loan", b(0.3, 25), b(0.45, 10), b(0.6, -15), b(null, -45));
        Characteristic bureau = c("bureauScore", "Bureau score", b(650d, -40), b(700d, -10), b(750d, 10), b(null, 30));
        Characteristic rotation = c("rotationShare", "Money rotation share", b(0.05, 10), b(0.2, 0), b(0.3, -20), b(null, -50));
        return List.of(
                new Definition("SC-KIRANA", "Kirana and retail stores", "KIRANA", 600, 19, 40, List.of(vintage, stability, bounces, foir,
                        c("stockToLoan", "Stock value / loan", b(0.5, -30), b(1d, -5), b(2d, 15), b(null, 25)),
                        c("shopSizeSqft", "Shop size (sq ft)", b(80d, -15), b(200d, 0), b(null, 15)),
                        c("balanceToIncome", "Average balance / income", b(0.1, -20), b(0.3, 0), b(null, 15)), rotation, bureau)),
                new Definition("SC-DAIRY", "Dairy", "DAIRY", 600, 19, 40, List.of(vintage, stability, bounces, foir,
                        c("animals", "Milch animals", b(1d, -35), b(3d, -5), b(6d, 15), b(null, 25)),
                        c("milkSocietyCard", "Milk society member", b(0d, -20), b(null, 20)),
                        c("householdSurplusRatio", "Household surplus / income", b(0d, -50), b(0.15, -10), b(0.3, 10), b(null, 20)), bureau)),
                new Definition("SC-TRANSPORT", "Transport operators", "TRANSPORT", 600, 19, 40, List.of(vintage, stability, bounces, foir,
                        c("vehicleAgeYears", "Vehicle age (years)", b(3d, 15), b(7d, 0), b(10d, -20), b(null, -40)),
                        c("vehiclesOwned", "Vehicles owned", b(1d, -5), b(3d, 10), b(null, 20)), rotation, bureau)),
                new Definition("SC-SALARIED", "Salaried", "SALARIED", 600, 19, 40, List.of(
                        c("vintage", "Years with employer", b(1d, -30), b(3d, 0), b(null, 20)), stability, bounces, foir, bureau,
                        c("balanceToIncome", "Average balance / salary", b(0.1, -20), b(0.4, 5), b(null, 15)))),
                new Definition("SC-MSME", "Other small businesses", "GENERAL", 600, 19, 40, List.of(vintage, stability, bounces, foir,
                        c("gstFiling", "GST filing regularity", b(0.6, -25), b(0.85, 0), b(null, 20)),
                        c("negativeMonths", "Months with negative net flow", b(0d, 15), b(2d, 0), b(null, -30)), rotation, bureau)));
    }
}
