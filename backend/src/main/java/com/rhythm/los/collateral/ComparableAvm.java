package com.rhythm.los.collateral;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Automated valuation from recent registered sales nearby: each sale's rate per sq ft is brought to today (market drift),
 * weighted by distance, age of the sale and how alike the size is, and the weighted median is applied to the property's area.
 * The spread of the rates sets the confidence. Shown beside the empanelled valuation as a cross-check; never used as the value.
 */
public final class ComparableAvm {
    private ComparableAvm() {}

    public record Sale(long id, String type, String pincode, String locality, Double lat, Double lng, double areaSqft, double price, LocalDate saleDate, String source) {}

    public record Subject(String type, String pincode, Double lat, Double lng, double areaSqft, String condition) {}

    public record Used(long id, String locality, double distanceKm, int monthsAgo, double areaSqft, double price, double ratePerSqft, double adjustedRate, double weight) {}

    public record Result(double value, double ratePerSqft, double low, double high, double confidence, int comparables, String basis, List<Used> used) {}

    public static final int MAX_MONTHS = 24;
    public static final double MAX_KM = 3.0;
    public static final int MIN_SALES = 3;

    /** null when there are fewer than three usable sales. */
    public static Result value(Subject s, List<Sale> sales, LocalDate today, double annualDrift) {
        if (s == null || s.areaSqft() <= 0) return null;
        List<Used> used = new ArrayList<>();
        boolean geo = s.lat() != null && s.lng() != null;
        for (Sale x : sales) {
            if (!s.type().equals(x.type()) || x.areaSqft() <= 0 || x.price() <= 0 || x.saleDate() == null || x.saleDate().isAfter(today)) continue;
            int months = (int) ChronoUnit.MONTHS.between(x.saleDate(), today);
            if (months > MAX_MONTHS) continue;
            double km;
            if (geo && x.lat() != null && x.lng() != null) {
                km = haversineKm(s.lat(), s.lng(), x.lat(), x.lng());
                if (km > MAX_KM) continue;
            } else {
                if (s.pincode() == null || x.pincode() == null) continue;
                if (s.pincode().equals(x.pincode())) km = 1.0;
                else if (s.pincode().length() >= 4 && x.pincode().startsWith(s.pincode().substring(0, 4))) km = 2.5;
                else continue;
            }
            double rate = x.price() / x.areaSqft();
            double adjusted = rate * Math.pow(1 + annualDrift, months / 12.0);
            double sizeRatio = Math.min(s.areaSqft(), x.areaSqft()) / Math.max(s.areaSqft(), x.areaSqft());
            double w = (1 / (1 + km)) * Math.exp(-months / 12.0) * (0.4 + 0.6 * sizeRatio);
            used.add(new Used(x.id(), x.locality(), round2(km), months, x.areaSqft(), x.price(), Math.round(rate), Math.round(adjusted), round2(w)));
        }
        if (used.size() < MIN_SALES) return null;
        used.sort(Comparator.comparingDouble(Used::weight).reversed());
        List<Used> top = used.subList(0, Math.min(12, used.size()));
        double median = weightedQuantile(top, 0.5), p25 = weightedQuantile(top, 0.25), p75 = weightedQuantile(top, 0.75);
        double f = "POOR".equals(s.condition()) ? 0.85 : "AVERAGE".equals(s.condition()) ? 0.95 : 1.0;
        double mean = top.stream().mapToDouble(Used::adjustedRate).average().orElse(median);
        double sd = Math.sqrt(top.stream().mapToDouble(u -> (u.adjustedRate() - mean) * (u.adjustedRate() - mean)).average().orElse(0));
        double cv = mean <= 0 ? 1 : sd / mean;
        double nearness = top.stream().mapToDouble(Used::distanceKm).average().orElse(MAX_KM) / MAX_KM;
        double conf = Math.max(0.2, Math.min(0.9, 0.35 + 0.05 * Math.min(top.size(), 8) - 0.8 * cv - 0.1 * nearness));
        String basis = top.size() + " sales within " + (geo ? Math.round(top.stream().mapToDouble(Used::distanceKm).max().orElse(0) * 10) / 10.0 + " km" : "the PIN code area")
                + " in the last " + top.stream().mapToInt(Used::monthsAgo).max().orElse(0) + " months, brought to today at " + Math.round(annualDrift * 1000) / 10.0 + "% a year";
        return new Result(Math.round(median * s.areaSqft() * f), Math.round(median), Math.round(p25 * s.areaSqft() * f), Math.round(p75 * s.areaSqft() * f),
                round2(conf), top.size(), basis, List.copyOf(top));
    }

    static double weightedQuantile(List<Used> us, double q) {
        List<Used> s = us.stream().sorted(Comparator.comparingDouble(Used::adjustedRate)).toList();
        double total = s.stream().mapToDouble(Used::weight).sum(), acc = 0;
        for (Used u : s) {
            acc += u.weight();
            if (acc >= q * total - 1e-9) return u.adjustedRate();
        }
        return s.get(s.size() - 1).adjustedRate();
    }

    static double haversineKm(double a1, double o1, double a2, double o2) {
        double r = 6371, dLat = Math.toRadians(a2 - a1), dLng = Math.toRadians(o2 - o1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(a1)) * Math.cos(Math.toRadians(a2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
