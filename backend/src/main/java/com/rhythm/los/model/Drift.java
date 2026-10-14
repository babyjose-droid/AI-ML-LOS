package com.rhythm.los.model;

import java.util.*;

/**
 * Population stability (PSI) per feature: the training values are cut into up to ten bins (deciles, with repeated
 * edges merged); live values are counted into the same bins. PSI = Σ (actual − expected) × ln(actual / expected), with
 * a small floor so an empty bin does not blow up. Below 0.10 is stable, 0.10–0.25 worth watching, above 0.25 drift.
 */
public final class Drift {
    private Drift() {}

    public record Baseline(List<Double> edges, List<Double> expected) {}
    public record Bin(String range, double expected, double actual) {}
    public record Psi(String feature, String label, double psi, String status, int live, List<Bin> bins) {}

    static final double FLOOR = 0.0001;

    public static Baseline baseline(List<Double> values) {
        List<Double> v = values.stream().filter(Objects::nonNull).sorted().toList();
        if (v.isEmpty()) return new Baseline(List.of(), List.of(1.0));
        TreeSet<Double> e = new TreeSet<>();
        for (int i = 1; i < 10; i++) e.add(v.get(Math.min(v.size() - 1, (int) Math.floor(i * v.size() / 10.0))));
        e.remove(v.get(v.size() - 1));   // the top edge would leave the last bin empty
        List<Double> edges = new ArrayList<>(e);
        return new Baseline(edges, shares(edges, v));
    }

    static int bin(List<Double> edges, double x) {
        int i = 0;
        while (i < edges.size() && x > edges.get(i)) i++;
        return i;
    }

    static List<Double> shares(List<Double> edges, List<Double> values) {
        double[] c = new double[edges.size() + 1];
        for (double x : values) c[bin(edges, x)]++;
        List<Double> out = new ArrayList<>();
        for (double x : c) out.add(values.isEmpty() ? 0 : x / values.size());
        return out;
    }

    public static Psi psi(String feature, String label, Baseline b, List<Double> live) {
        List<Double> act = shares(b.edges(), live);
        double psi = 0;
        List<Bin> bins = new ArrayList<>();
        for (int i = 0; i < b.expected().size(); i++) {
            double e = Math.max(FLOOR, b.expected().get(i)), a = Math.max(FLOOR, act.get(i));
            if (!live.isEmpty()) psi += (a - e) * Math.log(a / e);
            String lo = i == 0 ? "−∞" : fmt(b.edges().get(i - 1)), hi = i == b.edges().size() ? "∞" : fmt(b.edges().get(i));
            bins.add(new Bin(lo + " … " + hi, round(b.expected().get(i)), round(act.get(i))));
        }
        psi = round(psi);
        String status = live.size() < 30 ? "TOO_FEW" : psi < 0.10 ? "STABLE" : psi <= 0.25 ? "WATCH" : "DRIFT";
        return new Psi(feature, label, psi, status, live.size(), bins);
    }

    static double round(double x) { return Math.round(x * 10000) / 10000.0; }
    static String fmt(double x) { return Math.abs(x) >= 100 ? String.valueOf(Math.round(x)) : String.valueOf(Math.round(x * 100) / 100.0); }
}
