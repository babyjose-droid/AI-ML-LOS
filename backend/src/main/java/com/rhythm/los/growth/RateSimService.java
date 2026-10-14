package com.rhythm.los.growth;

import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.util.*;

/** Rate simulator over the current book's mix (by product and segment). */
@Service
public class RateSimService {
    public record Request(Double changeBps, String productCode, String segment, Double elasticityPerPp, Double adverseSelectionPerPp, Double costOfFunds) {}

    private final LoanBookService book;
    private final PolicyService policy;

    public RateSimService(LoanBookService book, PolicyService policy) {
        this.book = book;
        this.policy = policy;
    }

    public Map<String, Object> simulate(Request r, CurrentUser u) {
        double change = r.changeBps() == null ? 0 : r.changeBps();
        if (Math.abs(change) > 500) throw ApiException.unprocessable("BAD_CHANGE", "Change must be within ±500 basis points");
        PolicyParams pol = policy.live();
        RateSimulator.Assumptions a = new RateSimulator.Assumptions(
                clamp(r.elasticityPerPp(), 6, 0, 30), clamp(r.adverseSelectionPerPp(), 5, 0, 30), clamp(r.costOfFunds(), 11, 4, 20), pol.opexRate(), pol.maxRate());
        List<RateSimulator.Loan> mix = new ArrayList<>();
        for (LiveLoan l : book.visible(u)) {
            if (r.productCode() != null && !r.productCode().isBlank() && !r.productCode().equals(l.getProductCode())) continue;
            if (r.segment() != null && !r.segment().isBlank() && !r.segment().equals(l.getSegment())) continue;
            double pd = l.getAppPd() == null ? 0.05 : l.getAppPd().doubleValue();
            mix.add(new RateSimulator.Loan(l.getSanctionedAmount().doubleValue(), l.getRate().doubleValue(), pd, l.isSecured() ? pol.lgdSecured() : pol.lgdUnsecured(), l.getTenureMonths()));
        }
        if (mix.isEmpty()) throw ApiException.unprocessable("NO_BOOK", "No loans in the book for this product and segment: load the LMS feed first");
        RateSimulator.Outcome base = RateSimulator.run(mix, 0, a), scen = RateSimulator.run(mix, change / 100, a);
        List<RateSimulator.Outcome> curve = RateSimulator.curve(mix, a);
        RateSimulator.Outcome best = curve.stream().max(Comparator.comparingDouble(RateSimulator.Outcome::annualNetIncome)).orElse(base);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mixLoans", mix.size());
        m.put("assumptions", a);
        m.put("base", base);
        m.put("scenario", scen);
        m.put("curve", curve);
        m.put("bestChangeBps", Math.round(best.changePp() * 100));
        m.put("notes", List.of("Volume per year is the book's mix written again once; take-up falls " + a.elasticityPerPp() + "% per percentage point of rate.",
                "PD rises " + a.adverseSelectionPerPp() + "% per percentage point (better borrowers leave first); half of borrowers borrow to their EMI limit.",
                "Rates are capped at the policy maximum " + a.maxRate() + "% and floored at cost of funds plus operating cost."));
        return m;
    }

    static double clamp(Double v, double dflt, double lo, double hi) { return v == null ? dflt : Math.max(lo, Math.min(hi, v)); }
}
