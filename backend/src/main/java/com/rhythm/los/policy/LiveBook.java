package com.rhythm.los.policy;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.PdModel;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.portfolio.HistoricalLoan;
import com.rhythm.los.portfolio.HistoricalLoanRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/**
 * Simulation B (FR-POL-16): the effect of a policy change on loans already on the book. The live book is the loans
 * disbursed through Rhythm plus, until the LMS feed arrives in the integrations phase, a synthetic outstanding book
 * taken deterministically from the historical loans (months on book, outstanding and days past due derived from each
 * loan's known outcome). For each policy it shows which live loans would still be within policy, top-up and renewal
 * eligibility, classification (SMA / NPA) and behaviour PD, with a drill-down of loans whose status changes.
 */
@Component
public class LiveBook {
    public record LiveLoan(String ref, String name, String source, String segment, String product, double amount, double outstanding,
                           int tenure, int mob, int dpd, EngineInput input) {
        String classification() { return dpd == 0 ? "STANDARD" : dpd <= 30 ? "SMA-0" : dpd <= 60 ? "SMA-1" : dpd <= 90 ? "SMA-2" : "NPA"; }
        int remaining() { return Math.max(0, tenure - mob); }
    }

    private final HistoricalLoanRepository book;
    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;

    public LiveBook(HistoricalLoanRepository book, LoanApplicationRepository apps, DecisionRecordRepository decisions) {
        this.book = book;
        this.apps = apps;
        this.decisions = decisions;
    }

    public List<LiveLoan> loans() {
        List<LiveLoan> out = new ArrayList<>();
        for (LoanApplication a : apps.findAllByOrderByIdDesc()) {
            if (!"DISBURSED".equals(a.getAppState())) continue;
            DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
            if (d == null || d.getInputJson() == null) continue;
            EngineInput in;
            try { in = Json.read(d.getInputJson(), EngineInput.class); } catch (Exception e) { continue; }
            int mob = (int) Math.max(0, ChronoUnit.MONTHS.between(a.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toLocalDate(), LocalDate.now()));
            double amt = a.getLoanAmount().doubleValue();
            out.add(new LiveLoan(a.getAppNo(), a.getApplicantName(), "RHYTHM", a.getSegment(), a.getProductCode(), amt,
                    outstanding(amt, a.getTenureMonths(), mob), a.getTenureMonths(), mob, 0, in));
        }
        for (HistoricalLoan h : book.findAllByOrderByIdAsc()) {
            int k = Math.floorMod(h.getRef().hashCode(), 1000);
            if (k % 3 != 0) continue;                                  // about a third of the book is still outstanding
            EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
            int tenure = in.loan() == null ? 24 : Math.max(6, in.loan().tenure());
            int mob = 1 + k % Math.max(1, tenure - 1);
            int dpd = h.isOutcomeBad() ? new int[]{15, 45, 75, 120}[k % 4] : (k % 17 == 0 ? 10 : 0);
            double amt = h.getAmount().doubleValue();
            out.add(new LiveLoan(h.getRef(), in.name(), "SYNTHETIC", h.getSegment(), h.getProductCode(), amt, outstanding(amt, tenure, mob), tenure, mob, dpd, in));
        }
        return out;
    }

    static double outstanding(double amount, int tenure, int mob) {
        if (tenure <= 0) return 0;
        double f = Math.min(1, (double) mob / tenure);
        return Math.round(amount * (1 - f * (0.6 + 0.4 * f)));         // amortising: principal repaid slowly at first
    }

    /** Behaviour PD: the application PD moved by how the loan is actually paying. */
    static double behaviourPd(double pd, LiveLoan l) {
        if (l.dpd() > 90) return 1.0;
        if (l.dpd() > 60) return Math.max(0.5, pd);
        if (l.dpd() > 30) return Math.max(0.3, pd);
        if (l.dpd() > 0) return Math.max(0.1, pd * 2);
        return l.mob() >= 6 ? pd * 0.8 : pd;
    }

    static final class Side {
        int within, outside, topUp, renewal;
        double outsideOutstanding, topUpAmount, renewalAmount, expectedLoss;

        Map<String, Object> view() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("withinPolicy", within);
            m.put("outsidePolicy", outside);
            m.put("outsideOutstanding", Math.round(outsideOutstanding));
            m.put("topUpEligible", topUp);
            m.put("topUpAmount", Math.round(topUpAmount));
            m.put("renewalEligible", renewal);
            m.put("renewalAmount", Math.round(renewalAmount));
            m.put("expectedLoss", Math.round(expectedLoss));
            return m;
        }
    }

    record Status(boolean within, double topUp, boolean renewal) {}

    private static Status status(LiveLoan l, EngineResult r) {
        boolean within = r.decision().startsWith("APPROVE");
        boolean clean = l.dpd() == 0;
        double topUp = within && clean && l.mob() >= 6 && l.outstanding() <= 0.7 * l.amount()
                ? Math.max(0, Math.min(r.recAmt() - l.outstanding(), 0.5 * l.amount())) : 0;
        boolean renewal = within && clean && l.remaining() <= 3;
        return new Status(within, topUp, renewal);
    }

    /** policyFor gives the effective parameters (with overrides) for a loan's product and segment under each policy. */
    public Map<String, Object> simulate(Function<LiveLoan, PolicyParams> live, Function<LiveLoan, PolicyParams> draft, Function<EngineInput, PdModel> model) {
        List<LiveLoan> loans = loans();
        Side a = new Side(), b = new Side();
        Map<String, Map<String, Object>> classes = new LinkedHashMap<>();
        for (String c : List.of("STANDARD", "SMA-0", "SMA-1", "SMA-2", "NPA")) classes.put(c, new LinkedHashMap<>(Map.of("loans", 0, "outstanding", 0L, "behaviourPd", 0.0)));
        List<Map<String, Object>> drill = new ArrayList<>();
        double total = 0;
        long rhythm = 0;
        for (LiveLoan l : loans) {
            total += l.outstanding();
            if ("RHYTHM".equals(l.source())) rhythm++;
            PdModel pd = model.apply(l.input());
            PolicyParams pl = live.apply(l), pdft = draft.apply(l);
            EngineResult rl = UnderwritingEngine.underwrite(l.input(), pl, pd), rd = UnderwritingEngine.underwrite(l.input(), pdft, pd);
            Status sl = status(l, rl), sd = status(l, rd);
            double bpd = behaviourPd(rl.pd(), l);
            add(a, l, sl, bpd * (l.input().secured() ? pl.lgdSecured() : pl.lgdUnsecured()));
            add(b, l, sd, bpd * (l.input().secured() ? pdft.lgdSecured() : pdft.lgdUnsecured()));
            Map<String, Object> c = classes.get(l.classification());
            int n = (Integer) c.get("loans") + 1;
            c.put("behaviourPd", ((Double) c.get("behaviourPd") * (n - 1) + bpd) / n);
            c.put("loans", n);
            c.put("outstanding", (Long) c.get("outstanding") + Math.round(l.outstanding()));
            boolean changed = sl.within() != sd.within() || (sl.topUp() > 0) != (sd.topUp() > 0) || sl.renewal() != sd.renewal();
            if (changed && drill.size() < 50) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("ref", l.ref()); d.put("name", l.name()); d.put("source", l.source()); d.put("segment", l.segment()); d.put("product", l.product());
                d.put("outstanding", Math.round(l.outstanding())); d.put("mob", l.mob()); d.put("dpd", l.dpd()); d.put("classification", l.classification());
                d.put("behaviourPd", Math.round(bpd * 10000) / 10000.0);
                d.put("live", label(sl)); d.put("draft", label(sd));
                drill.add(d);
            }
        }
        for (Map<String, Object> c : classes.values()) c.put("behaviourPd", Math.round((Double) c.get("behaviourPd") * 10000) / 10000.0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loans", loans.size());
        out.put("outstanding", Math.round(total));
        out.put("source", rhythm == 0 ? "SYNTHETIC" : rhythm == loans.size() ? "RHYTHM" : "MIXED");
        out.put("classification", classes);
        out.put("live", a.view());
        out.put("draft", b.view());
        out.put("drillDown", drill);
        return out;
    }

    private static void add(Side s, LiveLoan l, Status st, double lossRate) {
        if (st.within()) s.within++; else { s.outside++; s.outsideOutstanding += l.outstanding(); }
        if (st.topUp() > 0) { s.topUp++; s.topUpAmount += st.topUp(); }
        if (st.renewal()) { s.renewal++; s.renewalAmount += l.amount(); }
        s.expectedLoss += lossRate * l.outstanding();
    }

    private static String label(Status s) {
        if (!s.within()) return "Outside policy";
        List<String> x = new ArrayList<>(List.of("Within policy"));
        if (s.topUp() > 0) x.add("top-up ₹" + Math.round(s.topUp()));
        if (s.renewal()) x.add("renewal");
        return String.join(" · ", x);
    }
}
