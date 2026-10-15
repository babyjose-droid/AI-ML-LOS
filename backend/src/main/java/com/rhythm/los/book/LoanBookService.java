package com.rhythm.los.book;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.Finance;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.policy.LiveBook;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * The live-loan book (FSD 17): loans disbursed here join the book at once; the LMS sends a daily or monthly feed of
 * outstanding, overdue, days past due and bounces (API or CSV). Every feed re-classifies each loan (SMA / NPA),
 * recomputes the Ind AS 109 ECL and the RBI minimum provision, and refreshes the early-warning score. A snapshot per
 * loan per feed date keeps the history for NPA movement and trends. Until the LMS is connected, a demo feed simulates
 * a month of repayments.
 */
@Service
public class LoanBookService {
    public record FeedRow(String loanRef, String appNo, BigDecimal outstanding, BigDecimal overdue, Integer dpd, Integer bouncesLast3m,
                          String lastPaymentDate, Integer paidEmis, String status,
                          String borrowerName, String segment, String productCode, BigDecimal sanctionedAmount, String disbursedOn,
                          Integer tenureMonths, BigDecimal rate, BigDecimal emi, Boolean secured) {}

    private final LiveLoanRepository loans;
    private final LoanSnapshotRepository snaps;
    private final LmsFeedRepository feeds;
    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;
    private final PolicyService policy;
    private final LiveBook liveBook;
    private final DataScope scope;
    private final AuditService audit;

    public LoanBookService(LiveLoanRepository loans, LoanSnapshotRepository snaps, LmsFeedRepository feeds, LoanApplicationRepository apps,
                           DecisionRecordRepository decisions, PolicyService policy, LiveBook liveBook, DataScope scope, AuditService audit) {
        this.loans = loans;
        this.snaps = snaps;
        this.feeds = feeds;
        this.apps = apps;
        this.decisions = decisions;
        this.policy = policy;
        this.liveBook = liveBook;
        this.scope = scope;
        this.audit = audit;
    }

    private LoanPartyRepository loanParties;
    private com.rhythm.los.party.ApplicationPartyRepository appParties;

    @org.springframework.beans.factory.annotation.Autowired
    void setParties(LoanPartyRepository lp, com.rhythm.los.party.ApplicationPartyRepository ap) { this.loanParties = lp; this.appParties = ap; }

    /** The co-applicants and guarantors who signed join the loan, so collections can reach them and the bureau report names them. */
    void copyParties(LiveLoan l) {
        if (loanParties == null || appParties == null || l.getApplicationId() == null || !loanParties.findByLoanIdOrderByIdAsc(l.getId()).isEmpty()) return;
        for (var p : appParties.findByApplicationIdOrderByIdAsc(l.getApplicationId())) {
            if (!p.active()) continue;
            LoanParty x = new LoanParty();
            x.setLoanId(l.getId());
            x.setApplicationPartyId(p.getId());
            x.setRole(p.getRole());
            x.setPartyType(p.getPartyType() == null ? "INDIVIDUAL" : p.getPartyType());
            x.setName(p.getName());
            x.setPan(p.getPan());
            x.setMobile(p.getMobile());
            x.setDob(p.getDob());
            x.setRelationship(p.getRelationship());
            x.setSignatoryName(p.getSignatoryName());
            loanParties.save(x);
        }
    }

    public java.util.List<LoanParty> parties(Long loanId) { return loanParties == null ? java.util.List.of() : loanParties.findByLoanIdOrderByIdAsc(loanId); }

    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    // ---------------------------------------------------------------- joining the book

    /** A loan disbursed here joins the book (the LMS feed takes over from the next run). */
    @Transactional
    public LiveLoan onDisbursed(LoanApplication a, Kfs kfs, String loanRef) {
        return loans.findByApplicationId(a.getId()).orElseGet(() -> {
            LiveLoan l = new LiveLoan();
            l.setLoanRef(loanRef);
            l.setApplicationId(a.getId());
            l.setAppNo(a.getAppNo());
            l.setBorrowerName(a.getApplicantName());
            l.setSegment(a.getSegment());
            l.setProductCode(a.getProductCode());
            l.setBranchId(a.getBranchId());
            l.setSanctionedAmount(money(kfs.sanctionedAmount()));
            l.setDisbursedOn(LocalDate.now());
            l.setTenureMonths(kfs.tenureMonths());
            l.setRate(money(kfs.ratePa()));
            l.setEmi(money(kfs.emi()));
            l.setSource("RHYTHM");
            l.setOutstanding(money(kfs.sanctionedAmount()));
            decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).ifPresent(d -> l.setAppPd(d.getPd()));
            recompute(l, LocalDate.now(), null);
            LiveLoan saved = loans.save(l);
            copyParties(saved);
            return saved;
        });
    }

    // ---------------------------------------------------------------- feed

    @Transactional
    public LmsFeed ingest(LocalDate feedDate, List<FeedRow> rows, String source, CurrentUser by) {
        if (feedDate == null) feedDate = LocalDate.now();
        if (feedDate.isAfter(LocalDate.now().plusYears(5))) throw ApiException.unprocessable("BAD_DATE", "Feed date is too far ahead");
        if (rows == null || rows.isEmpty()) throw ApiException.unprocessable("EMPTY_FEED", "The feed has no rows");
        if (rows.size() > 50_000) throw ApiException.unprocessable("FEED_TOO_LARGE", "At most 50,000 rows per feed");
        List<String> errors = new ArrayList<>();
        int ok = 0, n = 0;
        for (FeedRow r : rows) {
            n++;
            try {
                apply(r, feedDate);
                ok++;
            } catch (ApiException e) {
                if (errors.size() < 200) errors.add("Row " + n + (r.loanRef() == null ? "" : " (" + r.loanRef() + ")") + ": " + e.getMessage());
            }
        }
        LmsFeed f = new LmsFeed();
        f.setFeedDate(feedDate);
        f.setSource(source);
        f.setRowsTotal(rows.size());
        f.setAccepted(ok);
        f.setRejected(rows.size() - ok);
        f.setErrorsJson(errors.isEmpty() ? null : Json.write(errors));
        f.setCreatedBy(by.username());
        feeds.save(f);
        audit.record(by, "book.feed_loaded", "LMS_FEED", feedDate.toString(), null, source + " · " + ok + " of " + rows.size() + " rows");
        return f;
    }

    void apply(FeedRow r, LocalDate on) {
        if (r.loanRef() == null || !r.loanRef().matches("[A-Za-z0-9/_-]{3,40}")) throw ApiException.unprocessable("BAD_REF", "Loan id missing or invalid");
        if (r.outstanding() == null || r.outstanding().signum() < 0) throw ApiException.unprocessable("BAD_AMOUNT", "Outstanding is missing or negative");
        if (r.dpd() == null || r.dpd() < 0 || r.dpd() > 5000) throw ApiException.unprocessable("BAD_DPD", "Days past due missing or invalid");
        LiveLoan l = loans.findByLoanRef(r.loanRef()).orElseGet(() -> create(r));
        if (l.getLastFeedDate() != null && on.isBefore(l.getLastFeedDate())) throw ApiException.unprocessable("OLD_ROW", "A newer feed (" + l.getLastFeedDate() + ") is already loaded");
        Integer prevDpd = l.getLastFeedDate() == null ? null : l.getDpd();
        l.setOutstanding(r.outstanding().setScale(2, RoundingMode.HALF_UP));
        l.setOverdue(r.overdue() == null ? BigDecimal.ZERO : r.overdue().setScale(2, RoundingMode.HALF_UP));
        l.setDpd(r.dpd());
        l.setMaxDpd(Math.max(l.getMaxDpd(), r.dpd()));
        if (r.bouncesLast3m() != null) l.setBounces3m(r.bouncesLast3m());
        if (r.paidEmis() != null) l.setPaidEmis(r.paidEmis());
        if (r.lastPaymentDate() != null && !r.lastPaymentDate().isBlank()) l.setLastPaymentDate(date(r.lastPaymentDate()));
        if (r.status() != null && !r.status().isBlank()) {
            if (!Set.of("ACTIVE", "CLOSED", "WRITTEN_OFF").contains(r.status())) throw ApiException.unprocessable("BAD_STATUS", "Status must be ACTIVE, CLOSED or WRITTEN_OFF");
            l.setStatus(r.status());
        }
        recompute(l, on, prevDpd);
        loans.save(l);
        LoanSnapshot s = snaps.findByLoanIdAndFeedDate(l.getId(), on).orElse(null);
        if (s != null) snaps.delete(s);
        snaps.flush();
        snaps.save(new LoanSnapshot(l, on));
    }

    static LocalDate date(String s) {
        try { return LocalDate.parse(s.trim()); } catch (Exception e) { throw ApiException.unprocessable("BAD_DATE", "Dates must be YYYY-MM-DD: " + s); }
    }

    LiveLoan create(FeedRow r) {
        LiveLoan l = new LiveLoan();
        l.setLoanRef(r.loanRef());
        LoanApplication a = r.appNo() == null ? null : apps.findAll().stream().filter(x -> r.appNo().equals(x.getAppNo())).findFirst().orElse(null);
        if (a != null) {
            l.setApplicationId(a.getId());
            l.setAppNo(a.getAppNo());
            l.setBorrowerName(a.getApplicantName());
            l.setSegment(a.getSegment());
            l.setProductCode(a.getProductCode());
            l.setBranchId(a.getBranchId());
            decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).ifPresent(d -> l.setAppPd(d.getPd()));
        } else {
            if (r.borrowerName() == null || r.segment() == null || r.productCode() == null)
                throw ApiException.unprocessable("NEW_LOAN_DETAILS", "A loan new to the book needs borrower name, segment and product (or an application number)");
            l.setBorrowerName(r.borrowerName());
            l.setSegment(r.segment());
            l.setProductCode(r.productCode());
        }
        if (r.sanctionedAmount() == null || r.disbursedOn() == null || r.tenureMonths() == null || r.rate() == null)
            throw ApiException.unprocessable("NEW_LOAN_DETAILS", "A loan new to the book needs sanctioned amount, disbursal date, tenure and rate");
        l.setSanctionedAmount(r.sanctionedAmount());
        l.setDisbursedOn(date(r.disbursedOn()));
        l.setTenureMonths(r.tenureMonths());
        l.setRate(r.rate());
        l.setEmi(r.emi() != null ? r.emi() : money(Finance.emi(r.sanctionedAmount().doubleValue(), r.rate().doubleValue(), r.tenureMonths())));
        l.setSecured(Boolean.TRUE.equals(r.secured()));
        l.setSource("LMS");
        l.setOutstanding(r.outstanding());
        return loans.save(l);
    }

    /** Classification, behaviour PD, ECL, provision and early warning from the loan's current figures. */
    /** Re-runs classification, ECL, provision and early warning (after a restructure, say) and saves the loan. */
    @Transactional
    public LiveLoan refresh(LiveLoan l, LocalDate on) {
        recompute(l, on, l.getDpd());
        LiveLoan saved = loans.save(l);
        LoanSnapshot s = snaps.findByLoanIdAndFeedDate(saved.getId(), on).orElse(null);
        if (s != null) snaps.delete(s);
        snaps.flush();
        snaps.save(new LoanSnapshot(saved, on));
        return saved;
    }

    /** The loan's own "today": the later of today and its last feed date (the demo feed runs ahead). */
    public static LocalDate asOf(LiveLoan l) {
        return l.getLastFeedDate() != null && l.getLastFeedDate().isAfter(LocalDate.now()) ? l.getLastFeedDate() : LocalDate.now();
    }

    public LiveLoan get(Long id, CurrentUser u) {
        LiveLoan l = loans.findById(id).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!visible(scope.visibleBranches(u), l)) throw ApiException.notFound("Loan");
        return l;
    }

    void recompute(LiveLoan l, LocalDate on, Integer prevDpd) {
        PolicyParams pol = policy.live();
        BookRisk.Classification c = BookRisk.classify(l.getClassification(), l.getNpaSince(), l.getDpd(), l.getOverdue().doubleValue(), on);
        if (l.getNpaHoldUntil() != null) {
            // restructured: NPA through the specified period; a slip of more than 30 days restarts it
            if (l.getDpd() > 30 && on.isBefore(l.getNpaHoldUntil())) l.setNpaHoldUntil(on.plusMonths(12));
            if (on.isBefore(l.getNpaHoldUntil())) c = new BookRisk.Classification("NPA", l.getNpaSince() == null ? on : l.getNpaSince());
            else l.setNpaHoldUntil(null);
        }
        l.setClassification(c.cls());
        l.setNpaSince(c.npaSince());
        int mob = l.monthsOnBook(on);
        double bpd = BookRisk.behaviourPd(l.getAppPd() == null ? 0.05 : l.getAppPd().doubleValue(), l.getDpd(), l.getBounces3m(), mob);
        l.setBehaviourPd(BigDecimal.valueOf(bpd).setScale(6, RoundingMode.HALF_UP));
        double lgd = l.isSecured() ? pol.lgdSecured() : pol.lgdUnsecured();
        boolean closed = !"ACTIVE".equals(l.getStatus());
        BookRisk.Ecl e = BookRisk.ecl(l.getDpd(), c.cls(), l.isRestructured(), bpd, lgd, closed ? 0 : l.getOutstanding().doubleValue(), Math.max(1, l.getTenureMonths() - mob));
        l.setEclStage(e.stage());
        l.setEcl(money(e.ecl()));
        l.setProvisionIracp(money(closed ? 0 : BookRisk.provisionRate(c.cls(), c.npaSince(), l.isSecured(), on) * l.getOutstanding().doubleValue()));
        boolean fraud = l.getApplicationId() != null && apps.findById(l.getApplicationId()).map(a -> List.of("REVIEW", "BLOCK").contains(a.getFraudState())).orElse(false);
        BookRisk.Ews w = closed ? new BookRisk.Ews(0, List.of(), "Closed") : BookRisk.ews(l.getDpd(), prevDpd, l.getBounces3m(), l.getPaidEmis(), mob, bpd, l.isRestructured(), l.getLastPaymentDate(), on, fraud);
        l.setEwsScore(w.score());
        l.setEwsReasonsJson(Json.write(Map.of("signals", w.signals(), "action", w.action())));
        l.setLastFeedDate(on);
        l.setUpdatedAt(Instant.now());
    }

    /** Parses an LMS CSV (header row, comma-separated; the column names are the FeedRow field names). */
    public static List<FeedRow> parseCsv(String csv) {
        List<String> lines = csv.lines().filter(x -> !x.isBlank()).toList();
        if (lines.size() < 2) throw ApiException.unprocessable("EMPTY_FEED", "The CSV needs a header row and at least one loan");
        String[] head = lines.get(0).replace("﻿", "").split(",", -1);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < head.length; i++) col.put(head[i].trim(), i);
        if (!col.containsKey("loanRef") || !col.containsKey("outstanding") || !col.containsKey("dpd"))
            throw ApiException.unprocessable("BAD_HEADER", "The CSV needs at least the columns loanRef, outstanding and dpd");
        List<FeedRow> out = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] v = line.split(",", -1);
            java.util.function.Function<String, String> g = k -> col.containsKey(k) && col.get(k) < v.length && !v[col.get(k)].isBlank() ? v[col.get(k)].trim() : null;
            out.add(new FeedRow(g.apply("loanRef"), g.apply("appNo"), dec(g.apply("outstanding")), dec(g.apply("overdue")), integer(g.apply("dpd")),
                    integer(g.apply("bouncesLast3m")), g.apply("lastPaymentDate"), integer(g.apply("paidEmis")), g.apply("status"),
                    g.apply("borrowerName"), g.apply("segment"), g.apply("productCode"), dec(g.apply("sanctionedAmount")), g.apply("disbursedOn"),
                    integer(g.apply("tenureMonths")), dec(g.apply("rate")), dec(g.apply("emi")), g.apply("secured") == null ? null : Boolean.parseBoolean(g.apply("secured"))));
        }
        return out;
    }

    static BigDecimal dec(String s) { try { return s == null ? null : new BigDecimal(s); } catch (Exception e) { return null; } }
    static Integer integer(String s) { try { return s == null ? null : Integer.valueOf(s); } catch (Exception e) { return null; } }

    // ---------------------------------------------------------------- demo feed

    /**
     * Demo LMS: the first run seeds an outstanding book from the historical loans; every run simulates one more month of
     * repayments for every active loan (misses and cures drawn from each loan's behaviour PD) and loads it as a feed.
     */
    @Transactional
    public LmsFeed demoFeed(CurrentUser by) {
        if (loans.countBySource("DEMO") == 0) seedDemo();
        LocalDate last = loans.findAll().stream().map(LiveLoan::getLastFeedDate).filter(Objects::nonNull).max(LocalDate::compareTo).orElse(null);
        LocalDate on = last == null ? LocalDate.now() : last.plusMonths(1);
        List<FeedRow> rows = new ArrayList<>();
        for (LiveLoan l : loans.findByStatusOrderByIdAsc("ACTIVE")) rows.add(simulate(l, on));
        if (rows.isEmpty()) throw ApiException.conflict("NO_LOANS", "No active loans to simulate: disburse a loan or load an LMS feed first");
        return ingest(on, rows, "DEMO", by);
    }

    void seedDemo() {
        PolicyParams pol = policy.live();
        int i = 0;
        for (LiveBook.LiveLoan s : liveBook.loans()) {
            if (!"SYNTHETIC".equals(s.source()) || i >= 400) continue;
            if (loans.findByLoanRef("DEMO-" + s.ref()).isPresent()) continue;
            i++;
            LiveLoan l = new LiveLoan();
            l.setLoanRef("DEMO-" + s.ref());
            l.setBorrowerName(s.name() == null ? s.ref() : s.name());
            l.setSegment(s.segment());
            l.setProductCode(s.product());
            l.setSanctionedAmount(money(s.amount()));
            l.setDisbursedOn(LocalDate.now().minusMonths(s.mob()));
            l.setTenureMonths(s.tenure());
            double rate = 18;
            try {
                EngineResult r = UnderwritingEngine.underwrite(s.input(), pol);
                rate = r.rate();
                l.setAppPd(BigDecimal.valueOf(r.pd()).setScale(6, RoundingMode.HALF_UP));
            } catch (RuntimeException ignored) { }
            l.setRate(money(rate));
            l.setEmi(money(Finance.emi(s.amount(), rate, s.tenure())));
            l.setSecured(s.input() != null && s.input().secured());
            l.setSource("DEMO");
            l.setOutstanding(money(s.outstanding()));
            l.setDpd(s.dpd());
            l.setOverdue(money(s.dpd() == 0 ? 0 : Math.ceil(s.dpd() / 30.0) * l.getEmi().doubleValue()));
            l.setPaidEmis(Math.max(0, s.mob() - (int) Math.ceil(s.dpd() / 30.0)));
            l.setMaxDpd(s.dpd());
            recompute(l, LocalDate.now(), null);
            loans.save(l);
        }
    }

    FeedRow simulate(LiveLoan l, LocalDate on) {
        Random rnd = new Random((l.getLoanRef() + on).hashCode());
        double pd = l.getBehaviourPd() == null ? 0.05 : l.getBehaviourPd().doubleValue();
        double emi = l.getEmi().doubleValue();
        double out = l.getOutstanding().doubleValue();
        double overdue = l.getOverdue().doubleValue();
        int dpd = l.getDpd(), paid = l.getPaidEmis(), bounces = Math.max(0, l.getBounces3m() - 1);
        String last = l.getLastPaymentDate() == null ? null : l.getLastPaymentDate().toString();
        double monthlyMiss = Math.min(0.9, 1 - Math.pow(1 - Math.min(pd, 0.99), 1 / 12.0)) * 3;
        boolean pays = dpd == 0 ? rnd.nextDouble() > monthlyMiss : rnd.nextDouble() < Math.max(0.05, 0.6 * (1 - pd));
        double interest = out * l.getRate().doubleValue() / 1200;
        if (pays) {
            // pays this month's instalment and, when behind, the arrears too (a cure)
            double principal = Math.max(0, emi - interest);
            out = Math.max(0, out - principal - (dpd > 0 ? Math.max(0, overdue - emi) * 0.6 : 0));
            paid += 1 + (dpd > 0 ? dpd / 30 : 0);
            dpd = 0;
            overdue = 0;
            last = on.toString();
        } else {
            dpd += 30;
            overdue += emi;
            bounces += 1;
        }
        String status = paid >= l.getTenureMonths() || out < 1 ? "CLOSED" : "ACTIVE";
        if (status.equals("CLOSED")) { out = 0; overdue = 0; dpd = 0; }
        return new FeedRow(l.getLoanRef(), null, money(out), money(overdue), dpd, Math.min(3, bounces), last, paid, status,
                null, null, null, null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- views

    boolean visible(Set<Long> vis, LiveLoan l) { return vis == null || l.getBranchId() == null || vis.contains(l.getBranchId()); }

    /** Every loan the user may see (branch scope), no limit. */
    public List<LiveLoan> visible(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        return loans.findAll().stream().filter(l -> visible(vis, l)).toList();
    }

    public List<LiveLoan> list(CurrentUser u, String cls, boolean watchOnly, String q) {
        Set<Long> vis = scope.visibleBranches(u);
        return loans.findAll().stream().filter(l -> visible(vis, l))
                .filter(l -> cls == null || cls.isBlank() || cls.equals(l.getClassification()) || ("CLOSED".equals(cls) && !"ACTIVE".equals(l.getStatus())))
                .filter(l -> !watchOnly || (l.getEwsScore() >= 40 && "ACTIVE".equals(l.getStatus())))
                .filter(l -> q == null || q.isBlank() || l.getLoanRef().toLowerCase().contains(q.toLowerCase()) || l.getBorrowerName().toLowerCase().contains(q.toLowerCase())
                        || (l.getAppNo() != null && l.getAppNo().equalsIgnoreCase(q)))
                .sorted(Comparator.comparingInt(LiveLoan::getEwsScore).reversed().thenComparing(LiveLoan::getId))
                .limit(500).toList();
    }

    public Map<String, Object> detail(Long id, CurrentUser u) {
        LiveLoan l = loans.findById(id).orElseThrow(() -> ApiException.notFound("Loan"));
        if (!visible(scope.visibleBranches(u), l)) throw ApiException.notFound("Loan");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loan", l);
        m.put("ews", l.getEwsReasonsJson() == null ? Map.of() : Json.read(l.getEwsReasonsJson(), new TypeReference<Map<String, Object>>() {}));
        m.put("history", snaps.findByLoanIdOrderByFeedDateAsc(id));
        m.put("monthsOnBook", l.monthsOnBook(l.getLastFeedDate() == null ? LocalDate.now() : l.getLastFeedDate()));
        m.put("parties", parties(id).stream().map(p -> Map.of("id", p.getId(), "role", p.getRole(), "partyType", p.getPartyType(), "name", p.getName(),
                "pan", com.rhythm.los.common.Mask.pan(p.getPan()), "relationship", p.getRelationship() == null ? "" : p.getRelationship())).toList());
        return m;
    }

    public Map<String, Object> summary(CurrentUser u) {
        Set<Long> vis = scope.visibleBranches(u);
        List<LiveLoan> all = loans.findAll().stream().filter(l -> visible(vis, l)).toList();
        List<LiveLoan> act = all.stream().filter(l -> "ACTIVE".equals(l.getStatus())).toList();
        double os = act.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
        Map<String, Map<String, Object>> byClass = new LinkedHashMap<>();
        for (String c : List.of("STANDARD", "SMA-0", "SMA-1", "SMA-2", "NPA")) {
            List<LiveLoan> g = act.stream().filter(l -> c.equals(l.getClassification())).toList();
            double gos = g.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
            byClass.put(c, Map.of("loans", g.size(), "outstanding", Math.round(gos), "share", os == 0 ? 0 : Math.round(10000 * gos / os) / 10000.0,
                    "ecl", Math.round(g.stream().mapToDouble(l -> l.getEcl().doubleValue()).sum()),
                    "provisionIracp", Math.round(g.stream().mapToDouble(l -> l.getProvisionIracp().doubleValue()).sum())));
        }
        Map<String, Map<String, Object>> byStage = new LinkedHashMap<>();
        for (int s = 1; s <= 3; s++) {
            int st = s;
            List<LiveLoan> g = act.stream().filter(l -> l.getEclStage() == st).toList();
            double gos = g.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum(), ecl = g.stream().mapToDouble(l -> l.getEcl().doubleValue()).sum();
            byStage.put("Stage " + s, Map.of("loans", g.size(), "ead", Math.round(gos), "ecl", Math.round(ecl), "coverage", gos == 0 ? 0 : Math.round(10000 * ecl / gos) / 10000.0));
        }
        double npa = act.stream().filter(l -> "NPA".equals(l.getClassification())).mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
        double par30 = act.stream().filter(l -> l.getDpd() > 30).mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
        double ecl = act.stream().mapToDouble(l -> l.getEcl().doubleValue()).sum();
        double iracp = act.stream().mapToDouble(l -> l.getProvisionIracp().doubleValue()).sum();
        double npaProv = act.stream().filter(l -> "NPA".equals(l.getClassification())).mapToDouble(l -> Math.max(l.getEcl().doubleValue(), l.getProvisionIracp().doubleValue())).sum();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("loans", act.size());
        out.put("closed", all.size() - act.size());
        out.put("outstanding", Math.round(os));
        out.put("gnpa", os == 0 ? 0 : Math.round(10000 * npa / os) / 10000.0);
        out.put("nnpa", os == 0 ? 0 : Math.round(10000 * Math.max(0, npa - npaProv) / Math.max(1, os - npaProv)) / 10000.0);
        out.put("par30", os == 0 ? 0 : Math.round(10000 * par30 / os) / 10000.0);
        out.put("ecl", Math.round(ecl));
        out.put("provisionIracp", Math.round(iracp));
        out.put("provisionHeld", Math.round(act.stream().mapToDouble(l -> Math.max(l.getEcl().doubleValue(), l.getProvisionIracp().doubleValue())).sum()));
        out.put("impairmentReserve", Math.round(Math.max(0, iracp - ecl)));
        out.put("watchlist", act.stream().filter(l -> l.getEwsScore() >= 40).count());
        out.put("classification", byClass);
        out.put("stages", byStage);
        out.put("lastFeed", feeds.findTop20ByOrderByIdDesc().stream().findFirst().orElse(null));
        out.put("sources", Map.of("RHYTHM", all.stream().filter(l -> "RHYTHM".equals(l.getSource())).count(),
                "LMS", all.stream().filter(l -> "LMS".equals(l.getSource())).count(), "DEMO", all.stream().filter(l -> "DEMO".equals(l.getSource())).count()));
        return out;
    }

    public List<LmsFeed> feeds() { return feeds.findTop20ByOrderByIdDesc(); }
}
