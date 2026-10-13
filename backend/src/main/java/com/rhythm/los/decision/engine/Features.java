package com.rhythm.los.decision.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The model features of an application, computed exactly as the engine sees them. Used for training and scoring. */
public final class Features {
    private Features() {}

    public record Def(String key, String label, String reasonCode) {}

    public static final List<Def> ALL = List.of(
            new Def("stability", "Income stability", "RC-004"),
            new Def("foirExisting", "Existing obligations / income", "RC-002"),
            new Def("bounces", "EMI/cheque bounces (12m)", "RC-009"),
            new Def("negMonths", "Months with negative net flow", "RC-009"),
            new Def("balanceBuffer", "Average balance buffer", "RC-009"),
            new Def("vintage", "Business / employment vintage", null),
            new Def("thinFile", "No bureau history (thin file)", "RC-015"),
            new Def("bureauScoreGap", "Bureau score", "RC-001"),
            new Def("dpd12", "Delinquency in last 12m", "RC-005"),
            new Def("ever90", "Ever 90+ days past due", "RC-005"),
            new Def("enquiries6m", "Enquiries (6m)", "RC-006"),
            new Def("historyYears", "Credit history length", "RC-008"),
            new Def("gstFilingGap", "GST filing regularity", "RC-017"),
            new Def("gstTurnoverGap", "Bank vs GST turnover gap", "RC-010"),
            new Def("internalBad", "Prior loans with this NBFC", "RC-019"),
            new Def("loanToIncome", "Loan size / annual income", "RC-003"),
            new Def("declaredInflation", "Declared income above bank inflows", "RC-010"));

    public static Def def(String key) {
        return ALL.stream().filter(d -> d.key().equals(key)).findFirst().orElse(new Def(key, key, null));
    }

    public static Map<String, Double> of(EngineInput a) {
        List<Double> inf = a.bank().inflows(), out = a.bank().outflows();
        int n = inf.size();
        double mean = UnderwritingEngine.sum(inf) / n;
        double med = UnderwritingEngine.quantile(inf, 0.5), p25 = UnderwritingEngine.quantile(inf, 0.25);
        double sd = Math.sqrt(inf.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / n);
        double income = Math.max(1, Finance.round(Math.min(med, 1.15 * p25)));
        int neg = 0;
        for (int i = 0; i < n; i++) if (inf.get(i) - out.get(i) < 0) neg++;
        EngineInput.Bureau b = a.bureau();
        double existing = Math.max(a.bank().detectedEmi(), b != null ? b.emi() : 0);
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("stability", UnderwritingEngine.clamp(1 - sd / mean, 0, 1));
        f.put("foirExisting", existing / income);
        f.put("bounces", (double) a.bank().bounces());
        f.put("negMonths", (double) neg);
        f.put("balanceBuffer", Math.min(a.bank().avgBalance() / income, 1));
        f.put("vintage", Math.min(a.vintage(), 10));
        f.put("thinFile", b == null ? 1.0 : 0.0);
        f.put("bureauScoreGap", b == null ? 0 : (720 - b.score()) / 100.0);
        f.put("dpd12", b == null ? 0 : Math.min(b.maxDpd12(), 120) / 30.0);
        f.put("ever90", b != null && b.ever90() ? 1.0 : 0.0);
        f.put("enquiries6m", b == null ? 0.0 : b.enq6());
        f.put("historyYears", b == null ? 0 : Math.min(b.ageM(), 120) / 12.0);
        f.put("gstFilingGap", a.gst() == null ? 0 : 0.8 - a.gst().filing());
        f.put("gstTurnoverGap", a.gst() == null ? 0 : a.gst().gap());
        f.put("internalBad", a.internal() != null && a.internal().maxDpd() > 30 ? 1.0 : 0.0);
        f.put("loanToIncome", a.loan().amount() / (income * 12));
        f.put("declaredInflation", Math.max(0, a.declaredIncome() / mean - 1));
        return f;
    }
}
