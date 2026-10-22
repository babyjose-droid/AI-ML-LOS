package com.rhythm.los.pledge;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Pledge rules, pure. Securities PL-01..06 and deposits FD-01..05 decide the drawing power and whether the loan can be
 * sanctioned (everything but the lien) and disbursed (the lien too). margin() gives the shortfall that triggers a margin call.
 */
public final class PledgeRules {
    private PledgeRules() {}

    public record Check(String id, String label, String result, String reason, String ruling) {}
    public record Holding(String isin, String name, double quantity, Double price, double maxLtv, boolean approved, String category, LocalDate priceDate) {}
    public record Facts(String kind, String holder, String applicant, List<Holding> holdings, String depository, String dematId,
                        String issuer, String depositNo, Double principal, Double ratePa, LocalDate start, LocalDate maturity,
                        String lienState, double loan, int tenureMonths) {}
    public record Result(String status, double value, double drawingPower, List<Check> checks, boolean readyForSanction, boolean readyForDisbursement) {}

    /** RBI: an NBFC lends at most 50% of the value of shares; a deposit loan here at most 90% of the deposit with interest to date. */
    public static final double FD_LTV = 0.90;
    public static final double CONCENTRATION = 0.50;
    public static final int PRICE_STALE_DAYS = 3;
    public static final Set<String> HARD = Set.of("PL-03", "FD-03");
    static final Set<String> LIEN = Set.of("PL-06", "FD-05");

    static Check c(String id, String label, String result, String reason) { return new Check(id, label, result, reason, null); }

    public static Result evaluate(Facts f, Map<String, Map<String, Object>> rulings, LocalDate today) {
        List<Check> cs = new ArrayList<>();
        double value = 0, dp = 0;
        if ("SECURITIES".equals(f.kind())) {
            cs.add(holder(f, "PL-01"));
            List<Holding> priced = f.holdings().stream().filter(h -> h.price() != null).toList();
            for (Holding h : priced) {
                double v = h.quantity() * h.price();
                value += v;
                if (h.approved()) dp += v * h.maxLtv();
            }
            List<String> unapproved = f.holdings().stream().filter(h -> !h.approved() || h.price() == null).map(h -> h.name() == null ? h.isin() : h.name()).toList();
            if (f.holdings().isEmpty()) cs.add(c("PL-02", "Approved securities", "PENDING", "Upload the CAS statement or add the holdings"));
            else if (dp <= 0) cs.add(c("PL-02", "Approved securities", "FAIL", "None of the holdings is on the approved list"));
            else cs.add(c("PL-02", "Approved securities", "PASS", (f.holdings().size() - unapproved.size()) + " approved" + (unapproved.isEmpty() ? "" : "; not counted: " + String.join(", ", unapproved))));
            cs.add(f.holdings().isEmpty() ? c("PL-03", "Loan within the drawing power", "PENDING", "Drawing power needs the holdings")
                    : f.loan() <= dp + 1 ? c("PL-03", "Loan within the drawing power", "PASS", "Loan " + rupees(f.loan()) + " of drawing power " + rupees(dp) + " (value " + rupees(value) + ")")
                    : c("PL-03", "Loan within the drawing power", "FAIL", "Loan " + rupees(f.loan()) + " is above the drawing power " + rupees(dp) + "; pledge more or reduce the loan"));
            if (dp > 0) {
                double finalDp = dp;
                Optional<Holding> top = f.holdings().stream().filter(h -> h.approved() && h.price() != null).max(Comparator.comparingDouble(h -> h.quantity() * h.price() * h.maxLtv()));
                double share = top.map(h -> h.quantity() * h.price() * h.maxLtv() / finalDp).orElse(0.0);
                cs.add(share <= CONCENTRATION + 1e-9 ? c("PL-04", "Not concentrated in one security", "PASS", "Largest " + pct(share) + " of the drawing power (" + top.map(Holding::name).orElse("") + ")")
                        : c("PL-04", "Not concentrated in one security", "REFER", top.map(Holding::name).orElse("One security") + " is " + pct(share) + " of the drawing power (cap " + pct(CONCENTRATION) + ")"));
            } else cs.add(c("PL-04", "Not concentrated in one security", "PENDING", "Needs approved holdings"));
            Optional<LocalDate> oldest = priced.stream().map(Holding::priceDate).filter(Objects::nonNull).min(Comparator.naturalOrder());
            cs.add(oldest.isPresent() && ChronoUnit.DAYS.between(oldest.get(), today) > PRICE_STALE_DAYS
                    ? c("PL-05", "Prices up to date", "PENDING", "Prices are from " + oldest.get() + "; load today's price file")
                    : c("PL-05", "Prices up to date", "PASS", oldest.map(d -> "Prices as of " + d).orElse("No holdings priced yet")));
            cs.add(lien(f, "PL-06", "Pledge marked in the depository (" + Objects.toString(f.depository(), "CDSL / NSDL") + ")"));
        } else {
            cs.add(f.issuer() == null || f.depositNo() == null ? c("FD-01", "Deposit identified", "PENDING", "Upload the deposit receipt")
                    : c("FD-01", "Deposit identified", "PASS", f.issuer() + " · " + f.depositNo()));
            cs.add(holder(f, "FD-02"));
            if (f.principal() == null || f.principal() <= 0) cs.add(c("FD-03", "Loan within 90% of the deposit", "PENDING", "Deposit amount needed"));
            else {
                long days = f.start() == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(f.start(), today));
                value = f.principal() + f.principal() * (f.ratePa() == null ? 0 : f.ratePa()) / 100 * days / 365.0;
                dp = Math.floor(value * FD_LTV);
                cs.add(f.loan() <= dp + 1 ? c("FD-03", "Loan within 90% of the deposit", "PASS", "Loan " + rupees(f.loan()) + " of " + rupees(dp) + " (deposit with interest to date " + rupees(value) + ")")
                        : c("FD-03", "Loan within 90% of the deposit", "FAIL", "Loan " + rupees(f.loan()) + " is above " + rupees(dp) + " (90% of " + rupees(value) + ")"));
            }
            LocalDate loanEnd = today.plusMonths(f.tenureMonths());
            cs.add(f.maturity() == null ? c("FD-04", "Deposit outlasts the loan", "PENDING", "Maturity date needed")
                    : !f.maturity().isBefore(loanEnd) ? c("FD-04", "Deposit outlasts the loan", "PASS", "Matures " + f.maturity() + "; loan ends " + loanEnd)
                    : c("FD-04", "Deposit outlasts the loan", "REFER", "Matures " + f.maturity() + ", before the loan ends " + loanEnd + "; the maturity proceeds repay the loan"));
            cs.add(lien(f, "FD-05", "Lien marked by " + Objects.toString(f.issuer(), "the issuer")));
        }
        Map<String, Map<String, Object>> rule = rulings == null ? Map.of() : rulings;
        List<Check> out = new ArrayList<>();
        for (Check ch : cs) {
            Map<String, Object> r = rule.get(ch.id());
            out.add(r != null && "REFER".equals(ch.result()) && !HARD.contains(ch.id())
                    ? new Check(ch.id(), ch.label(), ch.result(), ch.reason(), "Exception approved by " + r.get("by") + ": " + r.get("note")) : ch);
        }
        boolean fail = out.stream().anyMatch(x -> "FAIL".equals(x.result()));
        boolean refer = out.stream().anyMatch(x -> "REFER".equals(x.result()) && x.ruling() == null);
        boolean pendingBeforeLien = out.stream().anyMatch(x -> "PENDING".equals(x.result()) && !LIEN.contains(x.id()));
        boolean lienPending = out.stream().anyMatch(x -> "PENDING".equals(x.result()) && LIEN.contains(x.id()));
        String status = fail ? "FAIL" : refer ? "REFER" : pendingBeforeLien || lienPending ? "PENDING" : "PASS";
        boolean sanction = !fail && !refer && !pendingBeforeLien;
        return new Result(status, round(value), round(dp), out, sanction, sanction && !lienPending);
    }

    static Check holder(Facts f, String id) {
        if (f.holder() == null) return c(id, "Held by the borrower", "PENDING", "Holder's name needed");
        return sameName(f.holder(), f.applicant()) ? c(id, "Held by the borrower", "PASS", f.holder())
                : c(id, "Held by the borrower", "REFER", "Held by " + f.holder() + ", not " + f.applicant() + " (a third-party pledge needs the holder to join)");
    }

    static Check lien(Facts f, String id, String label) {
        return "MARKED".equals(f.lienState()) ? c(id, label, "PASS", "Marked") : c(id, label, "PENDING", "Mark the lien before disbursement");
    }

    /** Shortfall when the loan outstanding is above the drawing power (0 when within). */
    public static double margin(double outstanding, double drawingPower) { return Math.max(0, Math.round((outstanding - drawingPower) * 100) / 100.0); }

    public static boolean sameName(String a, String b) {
        List<String> x = words(a), y = words(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        List<String> s = x.size() <= y.size() ? x : y, l = x.size() <= y.size() ? y : x;
        return l.containsAll(s);
    }
    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+"))
            if (!w.isBlank() && !Set.of("mr", "mrs", "ms", "dr", "shri", "smt").contains(w)) out.add(w);
        return out;
    }
    static double round(double v) { return Math.round(v * 100) / 100.0; }
    static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }

    public static String rupees(double v) {
        long r = Math.round(v);
        String s = Long.toString(Math.abs(r));
        if (s.length() > 3) {
            String head = s.substring(0, s.length() - 3), tail = s.substring(s.length() - 3);
            StringBuilder b = new StringBuilder();
            while (head.length() > 2) { b.insert(0, "," + head.substring(head.length() - 2)); head = head.substring(0, head.length() - 2); }
            s = head + b + "," + tail;
        }
        return (r < 0 ? "-₹" : "₹") + s;
    }
}
