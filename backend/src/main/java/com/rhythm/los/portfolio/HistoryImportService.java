package com.rhythm.los.portfolio;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Imports the lender's own past loans (from its LMS or spreadsheets) so the risk models can be trained on its book
 * rather than on synthetic data. Each row is turned into the same engine input the decision engine uses, with the
 * 12-month outcome: bad = 90+ days past due or written off; fraud = confirmed fraud.
 */
@Service
public class HistoryImportService {
    static final Set<Role> IMPORTERS = EnumSet.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    static final Set<String> SEGMENTS = Set.of("SALARIED", "MSME", "MICROFINANCE");
    public static final List<String> COLUMNS = List.of("ref", "productCode", "segment", "amount", "tenureMonths", "disbursedOn", "age", "vintageYears",
            "declaredMonthlyIncome", "essentialExpenses", "monthlyCredits", "avgMonthlyCredits", "avgMonthlyDebits", "avgBalance", "bounces", "existingEmi",
            "bureauScore", "activeLoans", "maxDpd12m", "ever90", "enquiries6m", "historyMonths", "gstFiling", "gstGrowth", "secured",
            "dupLinks", "docTamper", "maxDpd", "writtenOff", "fraud");

    private final HistoricalLoanRepository repo;
    private final AuditService audit;

    public HistoryImportService(HistoricalLoanRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public record Result(int rows, int added, int updated, int bad, int fraud, List<String> errors, String batch) {}

    @Transactional
    public Result importRows(List<Map<String, Object>> rows, CurrentUser by) {
        if (!IMPORTERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Credit managers, the CRO or an admin import loan history");
        if (rows == null || rows.isEmpty()) throw ApiException.unprocessable("EMPTY", "No loans to import");
        if (rows.size() > 20_000) throw ApiException.unprocessable("TOO_MANY", "At most 20,000 loans at a time");
        String batch = "IMP-" + LocalDate.now() + "-" + Integer.toHexString(new Random().nextInt(0xFFFF));
        List<String> errors = new ArrayList<>();
        int n = 0, added = 0, updated = 0, bad = 0, fraud = 0;
        for (Map<String, Object> r : rows) {
            n++;
            try {
                Parsed p = parse(r);
                HistoricalLoan h = repo.findByRef(p.ref()).orElse(null);
                if (h != null && !"IMPORT".equals(h.getSource())) throw new IllegalArgumentException("reference " + p.ref() + " is used by " + h.getSource().toLowerCase() + " data");
                if (h == null) {
                    h = new HistoricalLoan(p.ref(), "IMPORT", p.segment(), p.product(), money(p.input().loan().amount()), p.disbursedOn(), Json.write(p.input()), p.bad());
                    added++;
                } else {
                    h.setSegment(p.segment());
                    h.setProductCode(p.product());
                    h.setAmount(money(p.input().loan().amount()));
                    h.setDisbursedOn(p.disbursedOn());
                    h.setInputJson(Json.write(p.input()));
                    h.setOutcomeBad(p.bad());
                    updated++;
                }
                h.setOutcomeFraud(p.fraud());
                h.setMaxDpd(p.maxDpd());
                h.setImportedBy(by.username());
                h.setBatch(batch);
                repo.save(h);
                if (p.bad()) bad++;
                if (p.fraud()) fraud++;
            } catch (IllegalArgumentException e) {
                if (errors.size() < 200) errors.add("Row " + n + ": " + e.getMessage());
            }
        }
        audit.record(by, "models.history_imported", "MODEL", batch, null, (added + updated) + " of " + n + " loans (" + bad + " bad, " + fraud + " fraud)");
        return new Result(n, added, updated, bad, fraud, errors, batch);
    }

    record Parsed(String ref, String segment, String product, LocalDate disbursedOn, EngineInput input, boolean bad, boolean fraud, Integer maxDpd) {}

    /** One CSV row (already split into named columns) to the engine's input and outcome. */
    static Parsed parse(Map<String, Object> r) {
        String ref = str(r, "ref");
        if (ref == null || ref.length() > 40) throw new IllegalArgumentException("ref is required (at most 40 characters)");
        String seg = Optional.ofNullable(str(r, "segment")).map(String::toUpperCase).orElse("");
        if (!SEGMENTS.contains(seg)) throw new IllegalArgumentException("segment must be one of " + new TreeSet<>(SEGMENTS));
        double amount = req(r, "amount", 1_000, 50_000_000);
        int tenure = (int) req(r, "tenureMonths", 1, 360);
        int age = (int) req(r, "age", 18, 90);
        double income = req(r, "declaredMonthlyIncome", 1_000, 10_000_000);
        double essential = opt(r, "essentialExpenses", income * 0.4);
        List<Double> credits = series(str(r, "monthlyCredits"));
        if (credits.isEmpty()) {
            double avg = opt(r, "avgMonthlyCredits", -1);
            if (avg <= 0) throw new IllegalArgumentException("give monthlyCredits (a|b|c…) or avgMonthlyCredits");
            credits = Collections.nCopies(6, avg);
        }
        if (credits.size() < 3) throw new IllegalArgumentException("monthlyCredits needs at least 3 months");
        double avgDebits = opt(r, "avgMonthlyDebits", credits.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 0.85);
        List<Double> debits = Collections.nCopies(credits.size(), avgDebits);
        double existingEmi = opt(r, "existingEmi", 0);
        EngineInput.Bank bank = new EngineInput.Bank(credits, debits, opt(r, "avgBalance", credits.get(0) * 0.25), (int) opt(r, "bounces", 0), existingEmi, 0, "history", false);
        EngineInput.Bureau bureau = null;
        Double score = num(r, "bureauScore");
        if (score != null) {
            if (score < 300 || score > 900) throw new IllegalArgumentException("bureauScore must be 300 to 900 (leave blank when there was no bureau record)");
            bureau = new EngineInput.Bureau(score.intValue(), (int) opt(r, "activeLoans", 1), (int) opt(r, "maxDpd12m", 0), yes(r, "ever90"),
                    (int) opt(r, "enquiries6m", 0), (int) opt(r, "historyMonths", 24), existingEmi);
        }
        EngineInput.Gst gst = num(r, "gstFiling") == null ? null : new EngineInput.Gst(opt(r, "gstFiling", 1), opt(r, "gstGrowth", 0), 0);
        EngineInput.Fraud fr = new EngineInput.Fraud((int) opt(r, "dupLinks", 0), yes(r, "docTamper"), 1);
        EngineInput in = new EngineInput(ref, "Imported borrower", seg, age, opt(r, "vintageYears", 0), new EngineInput.Kyc(1, 1, 1, 1, 1),
                income, essential, bank, bureau, gst, null, "MICROFINANCE".equals(seg), yes(r, "secured"), fr, new EngineInput.Loan(amount, tenure), null);
        LocalDate disbursed = null;
        String d = str(r, "disbursedOn");
        if (d != null) {
            try { disbursed = LocalDate.parse(d); } catch (Exception e) { throw new IllegalArgumentException("disbursedOn must be YYYY-MM-DD"); }
            if (disbursed.isAfter(LocalDate.now())) throw new IllegalArgumentException("disbursedOn is in the future");
        }
        Double maxDpd = num(r, "maxDpd");
        if (maxDpd == null && str(r, "writtenOff") == null) throw new IllegalArgumentException("the outcome is missing: give maxDpd (worst days past due in the first 12 months) or writtenOff");
        boolean bad = maxDpd != null && maxDpd >= 90 || yes(r, "writtenOff");
        String product = str(r, "productCode");
        return new Parsed(ref, seg, product == null ? null : product.toUpperCase(), disbursed, in, bad, yes(r, "fraud"), maxDpd == null ? null : maxDpd.intValue());
    }

    static String str(Map<String, Object> r, String k) {
        Object v = r.get(k);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    static Double num(Map<String, Object> r, String k) {
        String s = str(r, k);
        if (s == null) return null;
        try { return Double.parseDouble(s.replace(",", "").replace("₹", "")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(k + " is not a number"); }
    }

    static double req(Map<String, Object> r, String k, double min, double max) {
        Double v = num(r, k);
        if (v == null) throw new IllegalArgumentException(k + " is required");
        if (v < min || v > max) throw new IllegalArgumentException(k + " must be between " + (long) min + " and " + (long) max);
        return v;
    }

    static double opt(Map<String, Object> r, String k, double dflt) { Double v = num(r, k); return v == null ? dflt : v; }

    static boolean yes(Map<String, Object> r, String k) {
        String s = str(r, k);
        return s != null && Set.of("Y", "YES", "TRUE", "1").contains(s.toUpperCase());
    }

    static List<Double> series(String s) {
        if (s == null) return List.of();
        List<Double> out = new ArrayList<>();
        for (String x : s.split("[|;]")) {
            if (x.isBlank()) continue;
            try { out.add(Double.parseDouble(x.trim().replace(",", ""))); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("monthlyCredits must be numbers separated by |"); }
        }
        return out;
    }

    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> by = new ArrayList<>();
        for (Object[] x : repo.summary()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("source", x[0]);
            r.put("loans", ((Number) x[1]).longValue());
            r.put("bad", x[2] == null ? 0L : ((Number) x[2]).longValue());
            r.put("fraud", x[3] == null ? 0L : ((Number) x[3]).longValue());
            by.add(r);
        }
        m.put("bySource", by);
        m.put("columns", COLUMNS);
        m.put("recent", repo.findTop50BySourceOrderByIdDesc("IMPORT").stream().map(h -> Map.of("ref", h.getRef(), "segment", h.getSegment(),
                "product", h.getProductCode() == null ? "" : h.getProductCode(), "amount", h.getAmount(), "bad", h.isOutcomeBad(), "fraud", h.isOutcomeFraud(),
                "batch", h.getBatch() == null ? "" : h.getBatch())).toList());
        return m;
    }
}
