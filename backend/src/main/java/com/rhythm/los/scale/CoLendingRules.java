package com.rhythm.los.scale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Co-lending eligibility per partner rule set (FR-CL-01..03) and the blended rate. Under the RBI Co-Lending
 * Arrangements Directions (2025) each lender keeps at least 10% of every loan, and the borrower pays the blended
 * rate: the weighted average of the two lenders' rates by share.
 */
public final class CoLendingRules {
    private CoLendingRules() {}

    public record Loan(String productCode, double amount, double rate, String band, boolean ntc, double vintageYears, boolean psl) {}
    public record Partner(String code, List<String> products, double maxTicket, List<String> bands, boolean ntcAllowed, double minVintage, boolean pslOnly,
                          double share, double partnerRate) {}

    public static final double MIN_RETENTION = 0.10;

    public static List<String> reasons(Partner p, Loan l) {
        List<String> r = new ArrayList<>();
        if (!p.products().contains(l.productCode())) r.add("Product " + l.productCode() + " not in the partner's programme");
        if (l.amount() > p.maxTicket()) r.add("Above the partner's maximum ticket");
        if (l.band() == null || !p.bands().contains(l.band())) r.add("Risk band " + (l.band() == null ? "unknown" : l.band()) + " not accepted");
        if (l.ntc() && !p.ntcAllowed()) r.add("New-to-credit not accepted");
        if (l.vintageYears() < p.minVintage()) r.add("Business vintage under " + p.minVintage() + " years");
        if (p.pslOnly() && !l.psl()) r.add("Not priority-sector (PSL) eligible");
        if (l.rate() < p.partnerRate()) r.add("Loan rate below the partner's rate");
        return r;
    }

    /** The originating NBFC's rate on its own share when the borrower pays the loan rate (the blended rate). */
    public static double nbfcYield(double loanRate, double share, double partnerRate) {
        return Math.round((loanRate - share * partnerRate) / (1 - share) * 100) / 100.0;
    }

    public static List<String> split(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
