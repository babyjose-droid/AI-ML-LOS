package com.rhythm.los.collateral;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Releasing or swapping collateral on a live loan. Pure rules, so they can be tested without the database.
 * <ul>
 *   <li>After the loan is closed every collateral is released (RBI, Sep 2023: original documents back within 30 days of full repayment,
 *       ₹5,000 for each day of delay).</li>
 *   <li>Before closure, one collateral may be released (after a part-payment) or swapped only if what remains still covers the
 *       outstanding within each type's maximum LTV.</li>
 * </ul>
 */
public final class CollateralChangeRules {
    private CollateralChangeRules() {}

    public static final int RELEASE_DAYS = 30;
    public static final long DELAY_COMPENSATION_PER_DAY = 5_000;

    /** A collateral held against the loan: its approved value and the type's maximum loan-to-value. */
    public record Item(long id, String label, double value, double maxLtv) {
        double lendable() { return Math.max(0, value) * Math.max(0, maxLtv); }
    }

    public record Result(boolean ok, String reason, double ltvBefore, Double ltvAfter, double lendableAfter, double shortfall) {}

    static double lendable(List<Item> items) { return items.stream().mapToDouble(Item::lendable).sum(); }
    static double value(List<Item> items) { return items.stream().mapToDouble(i -> Math.max(0, i.value())).sum(); }
    static Double ltv(double outstanding, List<Item> items) { double v = value(items); return v <= 0 ? null : outstanding / v; }

    public static Result release(double outstanding, boolean loanClosed, List<Item> held, long releaseId) {
        double before = ltv(outstanding, held) == null ? 0 : ltv(outstanding, held);
        if (held.stream().noneMatch(i -> i.id() == releaseId)) return new Result(false, "That collateral is not held against this loan", before, null, 0, 0);
        if (loanClosed || outstanding <= 0.5) return new Result(true, "The loan is closed: every collateral is released", before, 0.0, 0, 0);
        List<Item> rest = held.stream().filter(i -> i.id() != releaseId).toList();
        if (rest.isEmpty())
            return new Result(false, "It is the only collateral: it is released when the loan is closed (" + rupees(outstanding) + " still outstanding)", before, null, 0, outstanding);
        double room = lendable(rest);
        Double after = ltv(outstanding, rest);
        if (outstanding > room + 0.5)
            return new Result(false, "What remains covers only " + rupees(room) + " at the allowed LTV against " + rupees(outstanding) + " outstanding: "
                    + rupees(outstanding - room) + " more must be repaid first", before, after, room, outstanding - room);
        return new Result(true, "The remaining collateral covers the outstanding" + (after == null ? "" : " at " + pct(after) + " LTV"), before, after, room, 0);
    }

    public static Result substitute(double outstanding, List<Item> held, long oldId, Item replacement) {
        double before = ltv(outstanding, held) == null ? 0 : ltv(outstanding, held);
        if (held.stream().noneMatch(i -> i.id() == oldId)) return new Result(false, "That collateral is not held against this loan", before, null, 0, 0);
        if (replacement == null || replacement.value() <= 0)
            return new Result(false, "The new collateral needs an approved valuation first", before, null, 0, 0);
        List<Item> after = new java.util.ArrayList<>(held.stream().filter(i -> i.id() != oldId).toList());
        after.add(replacement);
        double room = lendable(after);
        Double ltvAfter = ltv(outstanding, after);
        if (outstanding > room + 0.5)
            return new Result(false, "With the new collateral the loan would be covered only up to " + rupees(room) + " at the allowed LTV against "
                    + rupees(outstanding) + " outstanding", before, ltvAfter, room, outstanding - room);
        return new Result(true, "Covered at " + pct(ltvAfter == null ? 0 : ltvAfter) + " LTV after the swap", before, ltvAfter, room, 0);
    }

    public static LocalDate releaseDue(LocalDate closedOn) { return closedOn == null ? null : closedOn.plusDays(RELEASE_DAYS); }

    /** Days late and the compensation owed to the borrower when documents go back after the due date. */
    public static long daysLate(LocalDate due, LocalDate handedOver) {
        if (due == null || handedOver == null) return 0;
        return Math.max(0, ChronoUnit.DAYS.between(due, handedOver));
    }

    public static long compensation(LocalDate due, LocalDate handedOver) { return daysLate(due, handedOver) * DELAY_COMPENSATION_PER_DAY; }

    static String rupees(double v) { return com.rhythm.los.common.Fmt.rupees(v); }
    static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }
}
