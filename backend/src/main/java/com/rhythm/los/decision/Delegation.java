package com.rhythm.los.decision;

import com.rhythm.los.decision.engine.EngineResult;

/**
 * Delegation of powers (board-approved matrix, illustrative values):
 *   L1 Credit officer  : up to ₹2 lakh, bands A-C, no policy deviations
 *   L2 Credit manager  : up to ₹10 lakh, band D or one deviation
 *   L3 CRO / committee : above ₹10 lakh, band E, two or more deviations, or any fraud flag
 */
public final class Delegation {
    private Delegation() {}

    public record Level(String level, String role, long deviations) {}

    public static Level of(EngineResult r) {
        long dev = r.referCount();
        double amt = r.recAmt();
        if (amt > 1_000_000 || "E".equals(r.band()) || dev >= 2 || !"CLEAR".equals(r.fraudStatus()))
            return new Level("L3", "CRO", dev);
        if (amt > 200_000 || "D".equals(r.band()) || dev == 1)
            return new Level("L2", "CREDIT_MANAGER", dev);
        return new Level("L1", "CREDIT_OFFICER", dev);
    }

    public static int rank(String level) {
        return switch (level) { case "L1" -> 1; case "L2" -> 2; case "L3" -> 3; default -> 99; };
    }
}
