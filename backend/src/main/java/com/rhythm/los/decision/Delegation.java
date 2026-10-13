package com.rhythm.los.decision;

import com.rhythm.los.decision.engine.EngineResult;

/**
 * Delegation of powers (board-approved matrix). The limits are data, edited by the admin in Roles and permissions
 * (sanction_limit table) and loaded here; the defaults are the illustrative values:
 *   L1 Credit officer  : up to ₹2 lakh, bands A-C, no policy deviations
 *   L2 Credit manager  : up to ₹10 lakh, band D or one deviation
 *   L3 CRO / committee : above ₹10 lakh, band E, two or more deviations, or any fraud flag
 */
public final class Delegation {
    private Delegation() {}

    public record Level(String level, String role, long deviations) {}

    /** l1Max/l2Max: largest amount (null = no limit); bands: worst band each level may approve; deviations: most allowed. */
    public record Matrix(Double l1Max, Double l2Max, String l1Band, String l2Band, int l1Deviations, int l2Deviations) {
        public static final Matrix DEFAULT = new Matrix(200_000d, 1_000_000d, "C", "D", 0, 1);
    }

    private static volatile Matrix matrix = Matrix.DEFAULT;

    public static Matrix matrix() { return matrix; }
    public static void use(Matrix m) { matrix = m == null ? Matrix.DEFAULT : m; }

    private static boolean worse(String band, String limit) { return band != null && limit != null && band.compareTo(limit) > 0; }
    private static boolean above(double amt, Double max) { return max != null && amt > max; }

    public static Level of(EngineResult r) {
        Matrix m = matrix;
        long dev = r.referCount();
        double amt = r.recAmt();
        if (above(amt, m.l2Max()) || worse(r.band(), m.l2Band()) || dev > m.l2Deviations() || !"CLEAR".equals(r.fraudStatus()))
            return new Level("L3", "CRO", dev);
        if (above(amt, m.l1Max()) || worse(r.band(), m.l1Band()) || dev > m.l1Deviations())
            return new Level("L2", "CREDIT_MANAGER", dev);
        return new Level("L1", "CREDIT_OFFICER", dev);
    }

    /** The level an amount alone needs (the same thresholds as {@link #of}). */
    public static String levelForAmount(double amount) {
        Matrix m = matrix;
        return above(amount, m.l2Max()) ? "L3" : above(amount, m.l1Max()) ? "L2" : "L1";
    }

    public static int rank(String level) {
        return switch (level) { case "L1" -> 1; case "L2" -> 2; case "L3" -> 3; default -> 99; };
    }
}
