package com.rhythm.los.decision.engine;

import com.rhythm.los.policy.PolicyParams;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static com.rhythm.los.common.Fmt.inr;
import static com.rhythm.los.decision.engine.Finance.round;

/**
 * Rhythm underwriting engine v1.
 *
 * Order: KYC -> bank cash flow -> bureau -> GST -> fraud -> PD model -> pricing -> affordability -> policy rules -> decision.
 * The PD model is logistic, so each feature's contribution to the log-odds is exact and is returned for explanation.
 * Coefficients are illustrative until the model is trained on the pilot NBFC's data (Phase 3).
 *
 * This class is pure: same input and policy always give the same result.
 */
public final class UnderwritingEngine {

    public static final String MODEL_THICK = "RHY-PD-THICK 1.4.2";
    public static final String MODEL_NTC = "RHY-PD-NTC 1.1.0";

    private static final double[][] ANCHORS = {{0.003, 900}, {0.01, 850}, {0.03, 780}, {0.07, 700}, {0.15, 620}, {0.5, 300}};

    private UnderwritingEngine() {}

    public static EngineResult underwrite(EngineInput a, PolicyParams p) {
        List<EngineResult.Step> steps = new ArrayList<>();
        List<EngineResult.RuleResult> rules = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();

        // 1. KYC
        EngineInput.Kyc k = a.kyc();
        double idScore = 0.35 * k.name() + 0.20 * k.dob() + 0.20 * k.id() + 0.15 * k.address() + 0.10 * k.contact();
        String kycStatus = idScore < p.kycFail() ? "FAIL" : idScore < p.kycReview() ? "REVIEW" : "PASS";
        steps.add(new EngineResult.Step("KYC & identity", Math.round(idScore * 100) + "% match",
                "PASS".equals(kycStatus) ? "ok" : "REVIEW".equals(kycStatus) ? "warn" : "bad"));

        // 2. Bank cash flow
        List<Double> inf = a.bank().inflows();
        List<Double> out = a.bank().outflows();
        int n = inf.size();
        double mean = sum(inf) / n;
        double med = quantile(inf, 0.5);
        double p25 = quantile(inf, 0.25);
        double sd = Math.sqrt(inf.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / n);
        double cv = sd / mean;
        double stab = clamp(1 - cv, 0, 1);
        double income = round(Math.min(med, 1.15 * p25));
        int negMonths = 0;
        for (int i = 0; i < n; i++) if (inf.get(i) - out.get(i) < 0) negMonths++;
        EngineInput.Bureau b = a.bureau();
        double existing = Math.max(a.bank().detectedEmi(), b != null ? b.emi() : 0);
        double foirEx = existing / income;
        double balRatio = a.bank().avgBalance() / income;
        double surplus = income - a.essential() - existing;
        steps.add(new EngineResult.Step("Bank cash-flow (" + n + " mo via AA)", "₹" + inr(income) + "/mo sustainable",
                stab > 0.65 ? "ok" : stab > 0.45 ? "warn" : "bad"));

        // 3. Bureau
        steps.add(new EngineResult.Step("Bureau",
                b != null ? "Score " + b.score() + " · " + b.active() + " active lines" : "No hit — thin file, alt-data model",
                b != null ? (b.maxDpd12() >= 30 || b.enq6() > p.maxEnq6() ? "warn" : "ok") : "info"));

        // 4. GST
        EngineInput.Gst g = a.gst();
        if (g != null) {
            steps.add(new EngineResult.Step("GST & business",
                    "Filing " + Math.round(g.filing() * 100) + "% · growth " + (g.growth() >= 0 ? "+" : "") + Math.round(g.growth() * 100) + "%",
                    g.filing() > 0.8 && g.gap() < 0.2 ? "ok" : "warn"));
        }

        // 5. Fraud
        EngineInput.Fraud f = a.fraud();
        double mism = a.declaredIncome() / mean;
        double fz = -4.2 + 1.0 * f.dupLinks() + 2.0 * Math.max(0, mism - 1.25) + 2.6 * (f.docTamper() ? 1 : 0)
                + 0.6 * Math.max(0, f.velocity() - 1) + 3 * (1 - idScore);
        int fraud = (int) round(sigmoid(fz) * 100);
        String fraudStatus = fraud >= p.fraudBlock() ? "BLOCK" : fraud >= p.fraudReview() ? "REVIEW" : "CLEAR";
        steps.add(new EngineResult.Step("Fraud & network", fraud + "/100 · " + fraudStatus,
                "CLEAR".equals(fraudStatus) ? "ok" : "REVIEW".equals(fraudStatus) ? "warn" : "bad"));

        // 6. PD model
        List<EngineResult.Contribution> c = new ArrayList<>();
        boolean thick = b != null;
        double intercept = thick ? -3.35 : -3.05;
        c.add(new EngineResult.Contribution("Income stability", 2.2 * (0.75 - stab), "RC-004"));
        c.add(new EngineResult.Contribution("Existing obligations / income", 2.5 * (foirEx - 0.20), "RC-002"));
        c.add(new EngineResult.Contribution("EMI/cheque bounces (12m)", 0.30 * a.bank().bounces(), "RC-009"));
        c.add(new EngineResult.Contribution("Months with negative net flow", 0.22 * negMonths, "RC-009"));
        c.add(new EngineResult.Contribution("Average balance buffer", -0.9 * (Math.min(balRatio, 1) - 0.25), "RC-009"));
        c.add(new EngineResult.Contribution("Business / employment vintage", -0.12 * (Math.min(a.vintage(), 10) - 3), null));
        if (thick) {
            c.add(new EngineResult.Contribution("Delinquency in last 12m", 0.014 * Math.min(b.maxDpd12(), 120) + (b.ever90() ? 1.2 : 0), "RC-005"));
            c.add(new EngineResult.Contribution("Enquiries (6m)", 0.13 * (b.enq6() - 2), "RC-006"));
            c.add(new EngineResult.Contribution("Bureau score", -0.0045 * (b.score() - 720), "RC-001"));
            c.add(new EngineResult.Contribution("Credit history length", -0.008 * (Math.min(b.ageM(), 120) - 36), "RC-008"));
        }
        if (g != null) {
            c.add(new EngineResult.Contribution("GST filing regularity", -1.2 * (g.filing() - 0.8), "RC-017"));
            c.add(new EngineResult.Contribution("GST turnover trend", -0.8 * g.growth(), "RC-017"));
            c.add(new EngineResult.Contribution("Bank vs GST turnover gap", 1.0 * (g.gap() - 0.1), "RC-010"));
        }
        if (a.internal() != null) {
            c.add(new EngineResult.Contribution("Prior loans with this NBFC", a.internal().maxDpd() > 30 ? 0.8 : -0.5, "RC-019"));
        }
        if ("MICROFINANCE".equalsIgnoreCase(a.segment()) && a.jlg()) {
            c.add(new EngineResult.Contribution("JLG group repayment record", -0.35, null));
        }
        double z = intercept + c.stream().mapToDouble(EngineResult.Contribution::value).sum();
        double pd = sigmoid(z);
        int score = pdToScore(pd);
        String band = band(pd);
        steps.add(new EngineResult.Step("Credit risk model",
                "PD " + String.format("%.1f", pd * 100) + "% · score " + score + " · band " + band,
                pd <= p.approvePd() ? "ok" : pd <= p.referPd() ? "warn" : "bad"));

        // 7. Pricing
        double lgd = a.secured() ? p.lgdSecured() : p.lgdUnsecured();
        double bandAdj = switch (band) { case "A" -> 0; case "B" -> 0.5; case "C" -> 1.5; case "D" -> 3; default -> 5; };
        double rawRate = p.baseRate() + pd * lgd * 100 + p.opexRate() * (a.loan().amount() < 100000 ? 1.5 : 1) + bandAdj
                - (a.internal() != null && a.internal().maxDpd() <= 30 ? 0.5 : 0);
        double rate = clamp(new BigDecimal(rawRate).setScale(2, RoundingMode.HALF_UP).doubleValue(), p.baseRate(), p.maxRate());

        // 8. Affordability
        double foirCap = p.maxFoir() * income - existing;
        double maxEmi = Math.max(0, round(Math.min(foirCap, surplus * p.safety())));
        double reqEmi = round(Finance.emi(a.loan().amount(), rate, a.loan().tenure()));
        double maxLoan = Math.floor(Finance.pv(maxEmi, rate, a.loan().tenure()) / 5000) * 5000;
        double foirPost = (existing + reqEmi) / income;
        double recAmt = Math.min(Math.min(a.loan().amount(), maxLoan), p.exposureCap());
        double recEmi = round(Finance.emi(recAmt, rate, a.loan().tenure()));
        steps.add(new EngineResult.Step("Affordability", "Max EMI ₹" + inr(maxEmi) + " · FOIR " + Math.round(foirPost * 100) + "%",
                reqEmi <= maxEmi ? "ok" : recAmt >= a.loan().amount() * 0.6 ? "warn" : "bad"));

        // 9. Policy rules: hard rejects first, then refers, then counter-offer
        RuleBook rb = new RuleBook(rules, reasons);
        rb.rule("R01", "Age within " + p.minAge() + "–" + p.maxAge(), a.age() >= p.minAge() && a.age() <= p.maxAge(), "REJECT", "RC-013");
        rb.rule("R02", "KYC identity match ≥ " + Math.round(p.kycFail() * 100) + "%", idScore >= p.kycFail(), "REJECT", "RC-011");
        rb.rule("R03", "KYC identity match ≥ " + Math.round(p.kycReview() * 100) + "%", idScore >= p.kycReview(), "REFER", "RC-011");
        rb.rule("R04", "Fraud score < " + fmt(p.fraudBlock()), fraud < p.fraudBlock(), "REJECT", "RC-012");
        rb.rule("R05", "Fraud score < " + fmt(p.fraudReview()), fraud < p.fraudReview(), "REFER", "RC-012");
        rb.rule("R06", "Bank data ≥ " + p.minBankMonths() + " months", n >= p.minBankMonths(), "REFER", "RC-013");
        if (thick) {
            rb.rule("R07", "No " + p.dpdReject() + "+ DPD in 12m", b.maxDpd12() < p.dpdReject(), "REJECT", "RC-005");
            rb.rule("R08", "Enquiries (6m) ≤ " + p.maxEnq6(), b.enq6() <= p.maxEnq6(), "REFER", "RC-006");
        }
        rb.rule("R09", "PD ≤ " + Math.round(p.referPd() * 100) + "% (decline band)", pd <= p.referPd(), "REJECT", "RC-001");
        rb.rule("R10", "PD ≤ " + Math.round(p.approvePd() * 100) + "% (auto-approve band)", pd <= p.approvePd(), "REFER", "RC-001");
        rb.rule("R11", "Some EMI capacity after obligations", maxEmi >= 0.3 * reqEmi && surplus > 0, "REJECT", "RC-003");
        rb.rule("R12", "Requested EMI within capacity", reqEmi <= maxEmi, "COUNTER", null);
        rb.rule("R13", "Amount ≤ auto-approval limit ₹" + inr(p.autoApproveLimit()), recAmt <= p.autoApproveLimit(), "REFER", "RC-014");
        if (!thick) reasons.add("RC-015");

        // 10. Decision
        String decision;
        if (rb.hard != null) decision = "REJECT";
        else if (!rb.refer.isEmpty()) decision = "REFER";
        else if (reqEmi > maxEmi) decision = "APPROVE_WITH_CONDITIONS";
        else decision = "APPROVE";
        if (reqEmi > maxEmi && !"REJECT".equals(decision)) reasons.add("RC-003");

        List<EngineResult.Contribution> sorted = new ArrayList<>(c);
        sorted.sort((x, y) -> Double.compare(y.value(), x.value()));
        List<EngineResult.Contribution> adverse = sorted.stream().filter(x -> x.value() > 0.05).limit(4).toList();
        List<EngineResult.Contribution> positiveAll = new ArrayList<>(sorted.stream().filter(x -> x.value() < -0.05).toList());
        Collections.reverse(positiveAll);
        List<EngineResult.Contribution> positive = positiveAll.stream().limit(4).toList();
        for (EngineResult.Contribution x : adverse) if (x.reasonCode() != null && !"APPROVE".equals(decision)) reasons.add(x.reasonCode());

        List<String> conditions = new ArrayList<>();
        if ("APPROVE_WITH_CONDITIONS".equals(decision))
            conditions.add("Sanction capped at ₹" + inr(recAmt) + " (EMI ₹" + inr(recEmi) + ") to stay within affordability");
        if (!thick && !"REJECT".equals(decision)) conditions.add("NACH mandate on primary account used for cash-flow analysis");
        if ("MSME".equalsIgnoreCase(a.segment()) && !"REJECT".equals(decision))
            conditions.add("Re-pull AA statement at month 6 for early-warning monitoring");

        StringBuilder narrative = new StringBuilder();
        if (!adverse.isEmpty()) narrative.append("Main risk drivers: ").append(labels(adverse)).append(". ");
        if (!positive.isEmpty()) narrative.append("Supporting factors: ").append(labels(positive)).append(".");
        if ("REFER".equals(decision)) {
            narrative.append(" Referred because: ").append(String.join("; ", rb.refer.stream().map(id -> rb.desc(id)).toList())).append(" not met.");
        }
        if ("REJECT".equals(decision)) narrative.append(" Hard rule failed: ").append(rb.desc(rb.hard)).append(".");

        return new EngineResult(decision, pd, score, band, fraud, fraudStatus, kycStatus, idScore, income, stab, negMonths,
                existing, surplus, foirEx, foirPost, maxEmi, reqEmi, maxLoan, recAmt, recEmi, rate, lgd,
                round(pd * lgd * recAmt), steps, rules, c, intercept, conditions, narrative.toString().trim(),
                new ArrayList<>(reasons), thick ? MODEL_THICK : MODEL_NTC, p.version());
    }

    private static final class RuleBook {
        final List<EngineResult.RuleResult> rules;
        final Set<String> reasons;
        String hard;
        final List<String> refer = new ArrayList<>();

        RuleBook(List<EngineResult.RuleResult> rules, Set<String> reasons) {
            this.rules = rules;
            this.reasons = reasons;
        }

        void rule(String id, String desc, boolean pass, String action, String rc) {
            rules.add(new EngineResult.RuleResult(id, desc, pass, pass ? "—" : action));
            if (!pass) {
                if (rc != null) reasons.add(rc);
                if ("REJECT".equals(action) && hard == null) hard = id;
                if ("REFER".equals(action)) refer.add(id);
            }
        }

        String desc(String id) {
            return rules.stream().filter(r -> r.id().equals(id)).findFirst().map(EngineResult.RuleResult::description).orElse(id);
        }
    }

    public static int pdToScore(double pd) {
        pd = clamp(pd, 0.003, 0.5);
        double l = Math.log(pd);
        for (int i = 1; i < ANCHORS.length; i++) {
            double a = ANCHORS[i - 1][0], sa = ANCHORS[i - 1][1], bb = ANCHORS[i][0], sb = ANCHORS[i][1];
            if (pd <= bb) {
                double t = (l - Math.log(a)) / (Math.log(bb) - Math.log(a));
                return (int) round(sa + (sb - sa) * t);
            }
        }
        return 300;
    }

    public static String band(double pd) {
        return pd <= 0.01 ? "A" : pd <= 0.03 ? "B" : pd <= 0.07 ? "C" : pd <= 0.15 ? "D" : "E";
    }

    private static String labels(List<EngineResult.Contribution> l) {
        return String.join(", ", l.stream().map(x -> x.label().toLowerCase()).toList());
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    static double sigmoid(double z) { return 1 / (1 + Math.exp(-z)); }

    static double clamp(double x, double a, double b) { return Math.max(a, Math.min(b, x)); }

    static double sum(List<Double> l) { return l.stream().mapToDouble(Double::doubleValue).sum(); }

    static double quantile(List<Double> a, double q) {
        List<Double> s = new ArrayList<>(a);
        Collections.sort(s);
        double pos = (s.size() - 1) * q;
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return s.get(lo) + (s.get(hi) - s.get(lo)) * (pos - lo);
    }
}
