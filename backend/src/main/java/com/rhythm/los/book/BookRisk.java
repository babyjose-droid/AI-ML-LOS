package com.rhythm.los.book;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Asset classification, provisioning, expected credit loss and early warning for one live loan. Pure functions.
 *
 * <ul>
 * <li>IRACP (RBI): SMA-0 1-30 days past due, SMA-1 31-60, SMA-2 61-90, NPA above 90. An NPA is upgraded only when
 *     the borrower has paid all arrears (overdue = 0), not merely when the days past due fall (RBI, 12 Nov 2021).</li>
 * <li>Minimum provisions (NBFC): standard 0.4%; sub-standard (NPA up to 12 months) 10%; doubtful secured 20/30/50%
 *     by age (1, 2-3, above 3 years doubtful), unsecured 100%.</li>
 * <li>Ind AS 109 ECL: stage 1 = 12-month PD; stage 2 (more than 30 days past due, or restructured) = lifetime PD;
 *     stage 3 (credit-impaired, NPA) = PD 1. ECL = PD × LGD × EAD.</li>
 * </ul>
 */
public final class BookRisk {
    private BookRisk() {}

    public record Classification(String cls, LocalDate npaSince) {}
    public record Ecl(int stage, double pd, double ecl) {}
    public record Signal(String reason, int points) {}
    public record Ews(int score, List<Signal> signals, String action) {}

    public static String bucket(int dpd) {
        return dpd <= 0 ? "STANDARD" : dpd <= 30 ? "SMA-0" : dpd <= 60 ? "SMA-1" : dpd <= 90 ? "SMA-2" : "NPA";
    }

    public static Classification classify(String current, LocalDate npaSince, int dpd, double overdue, LocalDate on) {
        String b = bucket(dpd);
        if ("NPA".equals(current)) {
            // stays NPA until every rupee of arrears is paid
            if (overdue > 0.5) return new Classification("NPA", npaSince == null ? on : npaSince);
            return new Classification(b.equals("NPA") ? "NPA" : "STANDARD", b.equals("NPA") ? npaSince : null);
        }
        if ("NPA".equals(b)) return new Classification("NPA", on.minusDays(dpd - 90L));
        return new Classification(b, null);
    }

    /** Behaviour PD: the application PD moved by how the loan is paying (12-month horizon). */
    public static double behaviourPd(double appPd, int dpd, int bounces3m, int mob) {
        double pd = appPd <= 0 ? 0.05 : appPd;
        if (dpd > 90) return 1.0;
        if (dpd > 60) pd = Math.max(0.5, pd);
        else if (dpd > 30) pd = Math.max(0.3, pd);
        else if (dpd > 0) pd = Math.max(0.1, pd * 2);
        else if (mob >= 6) pd = pd * 0.8;
        if (bounces3m >= 2) pd = Math.min(1, pd * 1.5);
        return Math.min(1, pd);
    }

    public static double provisionRate(String cls, LocalDate npaSince, boolean secured, LocalDate on) {
        if (!"NPA".equals(cls)) return 0.004;
        long months = npaSince == null ? 0 : ChronoUnit.MONTHS.between(npaSince, on);
        if (months <= 12) return 0.10;
        if (!secured) return 1.0;
        long doubtful = months - 12;
        return doubtful <= 12 ? 0.20 : doubtful <= 36 ? 0.30 : 0.50;
    }

    public static Ecl ecl(int dpd, String cls, boolean restructured, double pd12, double lgd, double ead, int remainingMonths) {
        int stage = "NPA".equals(cls) ? 3 : dpd > 30 || restructured ? 2 : 1;
        double pd = stage == 3 ? 1.0 : stage == 2 ? 1 - Math.pow(1 - Math.min(pd12, 0.9999), Math.max(1, remainingMonths) / 12.0) : pd12;
        return new Ecl(stage, Math.round(pd * 10000) / 10000.0, Math.round(pd * lgd * Math.max(0, ead)));
    }

    /** Early warning: signals that a loan is starting to go wrong, with a suggested action. Watch-list at 40 or more. */
    public static Ews ews(int dpd, Integer previousDpd, int bounces3m, int paidEmis, int mob, double behaviourPd, boolean restructured,
                          LocalDate lastPayment, LocalDate on, boolean fraudFlag) {
        List<Signal> s = new ArrayList<>();
        if (dpd > 0) s.add(new Signal(dpd + " days past due", Math.min(50, 20 + dpd / 3)));
        if (dpd > 0 && paidEmis == 0 && mob <= 3) s.add(new Signal("First instalment not paid", 25));
        if (previousDpd != null && dpd > previousDpd && previousDpd >= 0) s.add(new Signal("Days past due rising (" + previousDpd + " → " + dpd + ")", 15));
        if (bounces3m >= 2) s.add(new Signal(bounces3m + " bounced instalments in 3 months", 20));
        else if (bounces3m == 1) s.add(new Signal("A bounced instalment in 3 months", 8));
        if (behaviourPd >= 0.15 && dpd <= 90) s.add(new Signal("Behaviour PD " + Math.round(behaviourPd * 100) + "%", 15));
        if (lastPayment != null && ChronoUnit.DAYS.between(lastPayment, on) > 45 && dpd > 0) s.add(new Signal("No payment for " + ChronoUnit.DAYS.between(lastPayment, on) + " days", 10));
        if (restructured) s.add(new Signal("Restructured loan", 10));
        if (fraudFlag) s.add(new Signal("Fraud review on the application", 10));
        int score = Math.min(100, s.stream().mapToInt(Signal::points).sum());
        String action = dpd > 90 ? "NPA: recovery and legal review"
                : dpd > 60 ? "Field visit this week; assess restructuring"
                : dpd > 30 ? "Call today; field visit if no promise to pay"
                : dpd > 0 ? "Reminder (SMS/WhatsApp) and call"
                : score >= 40 ? "Courtesy call before the next due date" : "No action";
        return new Ews(score, s, action);
    }
}
