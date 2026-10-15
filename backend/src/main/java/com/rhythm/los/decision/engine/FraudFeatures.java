package com.rhythm.los.decision.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inputs of the fraud model, separate from the default (PD) model: a loan can be a good credit risk and still be a fraud.
 * The built-in fraud score uses fixed weights on these; a trained fraud model (approved by the CRO) replaces the weights.
 */
public final class FraudFeatures {
    private FraudFeatures() {}

    public static final Map<String, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put("dupLinks", "Shared phone, address or account with other applications");
        LABELS.put("docTamper", "Document tampering found");
        LABELS.put("velocity", "Applications in the last 30 days");
        LABELS.put("rotationShare", "Share of bank credits that rotate straight back out");
        LABELS.put("networkScore", "Application network risk (0 to 1)");
        LABELS.put("incomeGap", "Declared income above the bank credits");
        LABELS.put("idWeakness", "Weak identity match");
    }
    public static final List<String> ALL = List.copyOf(LABELS.keySet());

    public static String label(String key) { return LABELS.getOrDefault(key, key); }

    public static Map<String, Double> of(EngineInput a) {
        EngineInput.Kyc k = a.kyc();
        double idScore = 0.35 * k.name() + 0.20 * k.dob() + 0.20 * k.id() + 0.15 * k.address() + 0.10 * k.contact();
        List<Double> inf = a.bank().inflows();
        double mean = inf.isEmpty() ? 0 : inf.stream().mapToDouble(Double::doubleValue).sum() / inf.size();
        return of(a, idScore, mean);
    }

    static Map<String, Double> of(EngineInput a, double idScore, double mean) {
        EngineInput.Fraud f = a.fraud();
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("dupLinks", (double) f.dupLinks());
        m.put("docTamper", f.docTamper() ? 1.0 : 0.0);
        m.put("velocity", (double) f.velocity());
        m.put("rotationShare", f.rotationShare());
        m.put("networkScore", f.networkScore() / 100.0);
        m.put("incomeGap", mean <= 0 ? 0 : Math.max(0, a.declaredIncome() / mean - 1));
        m.put("idWeakness", 1 - idScore);
        return m;
    }

    /** The built-in score's log-odds (fixed weights); kept for applications decided before a fraud model is approved. */
    static double builtInZ(EngineInput a, double idScore, double mean) {
        EngineInput.Fraud f = a.fraud();
        double mism = a.declaredIncome() / mean;
        return -4.2 + 1.0 * f.dupLinks() + 2.0 * Math.max(0, mism - 1.25) + 2.6 * (f.docTamper() ? 1 : 0)
                + 0.6 * Math.max(0, f.velocity() - 1) + 3 * (1 - idScore)
                + 4.0 * Math.max(0, f.rotationShare() - 0.30) + f.networkScore() / 20.0;
    }

    public static double builtInProbability(EngineInput a) {
        EngineInput.Kyc k = a.kyc();
        double idScore = 0.35 * k.name() + 0.20 * k.dob() + 0.20 * k.id() + 0.15 * k.address() + 0.10 * k.contact();
        List<Double> inf = a.bank().inflows();
        double mean = inf.isEmpty() ? 1 : inf.stream().mapToDouble(Double::doubleValue).sum() / inf.size();
        return 1 / (1 + Math.exp(-builtInZ(a, idScore, mean)));
    }

    static double z(PdModel m, Map<String, Double> fx) {
        double z = m.intercept();
        for (PdModel.Term t : m.terms()) z += t.weight() * (fx.getOrDefault(t.feature(), 0.0) - t.mean());
        return z;
    }
}
