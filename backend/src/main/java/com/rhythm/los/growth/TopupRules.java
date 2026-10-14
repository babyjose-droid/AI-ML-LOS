package com.rhythm.los.growth;

import com.rhythm.los.book.LiveLoan;

import java.util.ArrayList;
import java.util.List;

/** Who can be offered a top-up, for how much and at what rate. A good payer, seasoned, never seriously late, not restructured. */
public final class TopupRules {
    private TopupRules() {}

    public record Check(boolean eligible, List<String> reasons, double amount, double rate, int tenureMonths) {}

    public static final int MIN_MOB = 6;
    public static final double EXPOSURE_MULTIPLE = 1.5;
    public static final double MIN_TOPUP = 25_000;

    public static Check check(LiveLoan l, int mob, double exposureCap) {
        List<String> why = new ArrayList<>();
        if (!"ACTIVE".equals(l.getStatus())) why.add("Loan is not active");
        if (l.getDpd() > 0 || !"STANDARD".equals(l.getClassification())) why.add("Currently overdue (" + l.getClassification() + ")");
        if (l.getMaxDpd() > 30) why.add("Was more than 30 days late (worst " + l.getMaxDpd() + ")");
        if (mob < MIN_MOB) why.add("Only " + mob + " months on book (needs " + MIN_MOB + ")");
        if (l.getPaidEmis() < MIN_MOB) why.add("Only " + l.getPaidEmis() + " instalments paid (needs " + MIN_MOB + ")");
        if (l.getBounces3m() > 0) why.add("Bounced instalment in the last 3 months");
        if (l.isRestructured()) why.add("Restructured loan");
        double bpd = l.getBehaviourPd() == null ? 0.05 : l.getBehaviourPd().doubleValue();
        if (bpd >= 0.10) why.add("Behaviour PD " + Math.round(bpd * 100) + "% (needs under 10%)");
        if (l.getEwsScore() >= 40) why.add("On the early-warning watchlist");
        double room = Math.min(EXPOSURE_MULTIPLE * l.getSanctionedAmount().doubleValue(), exposureCap) - l.getOutstanding().doubleValue();
        double amount = Math.floor(Math.max(0, room) / 10_000) * 10_000;
        if (amount < MIN_TOPUP) why.add("Top-up room ₹" + Math.round(Math.max(0, room)) + " is under ₹25,000");
        double rate = l.getRate().doubleValue() - (bpd < 0.03 ? 0.5 : 0);
        return new Check(why.isEmpty(), why, why.isEmpty() ? amount : 0, Math.round(rate * 100) / 100.0, l.getTenureMonths());
    }
}
