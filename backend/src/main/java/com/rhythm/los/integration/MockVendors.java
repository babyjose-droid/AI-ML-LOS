package com.rhythm.los.integration;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.integration.VendorModels.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Deterministic mock vendors for development and testing.
 *
 * Five demo personas (fixed PANs) return exactly the data used in the prototype.
 * Any other PAN gets data generated from the declared income, so every test is repeatable.
 *
 * Scenario PANs (the 4 digits in the PAN decide the scenario):
 *   9001-9099  bureau no-hit (thin file)
 *   9101-9199  AA times out twice, then succeeds (shows automatic retry)
 *   9201-9299  AA fails until someone retries manually from the integration log (shows DLQ + manual retry)
 *   9301-9399  KYC address and contact mismatch (goes to KYC review)
 *   9401-9499  fraud signals: declared income far above bank inflows
 *   9501-9599  penny drop fails name match (disbursement fails, then can be retried)
 */
@Component
public class MockVendors implements Vendors.KycProvider, Vendors.AccountAggregator, Vendors.CreditBureau,
        Vendors.GstProvider, Vendors.BankVerification, Vendors.PayoutProvider {

    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    record Persona(String name, double[] kyc, double[] inflows, double[] outflows, double avgBalance, int bounces, double detectedEmi,
                   BureauReport bureau, GstProfile gst) {}

    static final Map<String, Persona> PERSONAS = Map.of(
            "RAMPP1001R", new Persona("Ramesh Patil", new double[]{0.97, 1, 1, 0.9, 1},
                    new double[]{54e3, 58e3, 61e3, 57e3, 66e3, 72e3, 59e3, 55e3, 63e3, 60e3, 68e3, 64e3},
                    new double[]{50e3, 52e3, 57e3, 55e3, 58e3, 64e3, 61e3, 51e3, 56e3, 55e3, 60e3, 58e3}, 21000, 1, 4500,
                    new BureauReport("CIBIL", false, 0, 0, 0, false, 0, 0, 0),
                    new GstProfile("27RAMPP1001R1Z5", 0.92, 0.12, 0.08)),
            "PRIPN1002P", new Persona("Priya Nair", new double[]{1, 1, 1, 0.95, 1},
                    new double[]{72e3, 72e3, 74e3, 72e3, 72e3, 85e3, 72e3, 72e3, 72e3, 74e3, 72e3, 72e3},
                    new double[]{66e3, 64e3, 70e3, 65e3, 63e3, 79e3, 66e3, 67e3, 64e3, 69e3, 65e3, 66e3}, 26000, 0, 10000,
                    new BureauReport("CIBIL", true, 742, 3, 0, false, 4, 62, 10000), null),
            "LAKPD1003L", new Persona("Lakshmi Devi", new double[]{0.95, 1, 1, 0.85, 1},
                    new double[]{15e3, 14e3, 21e3, 26e3, 27e3, 23e3, 16e3, 13e3, 19e3, 24e3, 26e3, 21e3},
                    new double[]{15e3, 16e3, 18e3, 20e3, 21e3, 20e3, 17e3, 17e3, 17e3, 18e3, 20e3, 19e3}, 5200, 0, 1800,
                    new BureauReport("CRIF", false, 0, 0, 0, false, 0, 0, 0), null),
            "SURPY1004S", new Persona("Suresh Yadav", new double[]{0.96, 1, 1, 0.8, 1},
                    new double[]{92e3, 78e3, 98e3, 74e3, 88e3, 104e3, 70e3, 92e3, 80e3, 97e3, 76e3, 86e3},
                    new double[]{88e3, 80e3, 90e3, 76e3, 84e3, 95e3, 73e3, 87e3, 79e3, 90e3, 74e3, 82e3}, 14000, 1, 14000,
                    new BureauReport("CIBIL", true, 702, 5, 30, false, 7, 84, 16500),
                    new GstProfile("09SURPY1004S1Z2", 0.75, -0.05, 0.18)),
            "ANIPK1005A", new Persona("Anil Kumar", new double[]{0.82, 1, 1, 0.55, 0.6},
                    new double[]{38e3, 37e3, 40e3, 38e3, 36e3, 41e3, 39e3, 38e3},
                    new double[]{36e3, 37e3, 39e3, 37e3, 35e3, 40e3, 38e3, 37e3}, 3500, 0, 0,
                    new BureauReport("CIBIL", true, 705, 1, 0, false, 5, 14, 2500), null));

    static int scenario(String pan) {
        try { return Integer.parseInt(pan.substring(5, 9)); } catch (Exception e) { return 0; }
    }

    static boolean in(int code, int from, int to) { return code >= from && code <= to; }

    private int count(LoanApplication app, String op) {
        return calls.computeIfAbsent(app.getId() + ":" + op, k -> new AtomicInteger()).incrementAndGet();
    }

    private static Random rng(LoanApplication app) {
        return new Random(app.getPan().hashCode() * 31L + 7);
    }

    @Override public String name() { return "MOCK"; }

    // ---- KYC ----
    @Override
    public KycBundle verify(LoanApplication app) {
        Persona p = PERSONAS.get(app.getPan());
        double[] s = p != null ? p.kyc() : in(scenario(app.getPan()), 9301, 9399)
                ? new double[]{0.8, 1, 1, 0.3, 0.3} : new double[]{nameMatch(app.getApplicantName(), app.getApplicantName()), 1, 1, 0.92, 1};
        String regName = p != null ? p.name() : app.getApplicantName();
        double nameScore = s[0];
        return new KycBundle(
                new PanResult(app.getPan(), regName.toUpperCase(), "VALID", app.getDob().toString()),
                new AadhaarResult("XXXX-XXXX-" + String.format("%04d", Math.abs(app.getPan().hashCode()) % 10000), regName,
                        app.getDob().toString(), app.getCity(), s[3]),
                new CkycResult(true, "CKYC" + Math.abs(app.getPan().hashCode()) % 100000000L, s[4]),
                nameScore, s[1], s[2], s[3], s[4]);
    }

    /** Token overlap name match, 0..1. */
    static double nameMatch(String a, String b) {
        Set<String> x = new HashSet<>(Arrays.asList(a.toLowerCase().trim().split("\\s+")));
        Set<String> y = new HashSet<>(Arrays.asList(b.toLowerCase().trim().split("\\s+")));
        Set<String> inter = new HashSet<>(x);
        inter.retainAll(y);
        return Math.min(1.0, Math.round(100.0 * inter.size() / Math.max(x.size(), y.size())) / 100.0);
    }

    // ---- Account Aggregator ----
    @Override
    public AaStatement fetchStatements(LoanApplication app, int months) {
        int code = scenario(app.getPan());
        int n = count(app, "AA");
        if (in(code, 9101, 9199) && n <= 2) throw new VendorException("FIP response timed out after 30s", true);
        if (in(code, 9201, 9299) && n <= 3) throw new VendorException("FIP unavailable (HDFC Bank FIP down)", true);
        Persona p = PERSONAS.get(app.getPan());
        if (p != null) {
            return new AaStatement("Demo Bank", "XXXXXX" + app.getPan().substring(5, 9), p.inflows().length,
                    list(p.inflows()), list(p.outflows()), p.avgBalance(), p.bounces(), p.detectedEmi());
        }
        Random r = rng(app);
        double base = app.getDeclaredMonthlyIncome().doubleValue() * (in(code, 9401, 9499) ? 0.4 : 0.95);
        List<Double> inflows = new ArrayList<>(), outflows = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            double inflow = Math.round(base * (0.9 + 0.2 * r.nextDouble()) / 100) * 100.0;
            inflows.add(inflow);
            outflows.add(Math.round(inflow * (0.84 + 0.1 * r.nextDouble()) / 100) * 100.0);
        }
        return new AaStatement("Demo Bank", "XXXXXX" + app.getPan().substring(5, 9), months, inflows, outflows,
                Math.round(base * 0.35), r.nextInt(10) < 8 ? 0 : 1, Math.round(base * 0.06 / 100) * 100.0);
    }

    // ---- Bureau ----
    @Override
    public BureauReport pull(LoanApplication app) {
        Persona p = PERSONAS.get(app.getPan());
        if (p != null) return p.bureau();
        if (in(scenario(app.getPan()), 9001, 9099)) return new BureauReport("CIBIL", false, 0, 0, 0, false, 0, 0, 0);
        Random r = rng(app);
        double emi = Math.round(app.getDeclaredMonthlyIncome().doubleValue() * 0.06 / 100) * 100.0;
        return new BureauReport("CIBIL", true, 700 + r.nextInt(80), 1 + r.nextInt(3), 0, false, 1 + r.nextInt(3), 24 + r.nextInt(60), emi);
    }

    // ---- GST ----
    @Override
    public GstProfile profile(LoanApplication app) {
        Persona p = PERSONAS.get(app.getPan());
        if (p != null) return p.gst();
        if (!"MSME".equals(app.getSegment())) return null;
        Random r = rng(app);
        return new GstProfile("27" + app.getPan() + "1Z5", 0.85 + 0.15 * r.nextDouble(), 0.02 + 0.1 * r.nextDouble(), 0.05 + 0.08 * r.nextDouble());
    }

    // ---- Penny drop and payout ----
    @Override
    public PennyDropResult pennyDrop(LoanApplication app) {
        boolean bad = in(scenario(app.getPan()), 9501, 9599) && count(app, "PENNY") <= 1;
        return new PennyDropResult(true, bad ? "SOMEONE ELSE" : app.getApplicantName().toUpperCase(), bad ? 0.2 : 0.98,
                "PD" + System.nanoTime() % 1_000_000_000L);
    }

    @Override
    public PayoutResult pay(LoanApplication app, double amount, String reference) {
        return new PayoutResult(true, "UTR" + Math.abs((reference + amount).hashCode()), null);
    }

    private static List<Double> list(double[] a) {
        List<Double> l = new ArrayList<>(a.length);
        for (double d : a) l.add(d);
        return l;
    }
}
