package com.rhythm.los.compliance;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.disbursement.Disbursement;
import com.rhythm.los.disbursement.DisbursementRepository;
import com.rhythm.los.growth.CollectionContact;
import com.rhythm.los.growth.CollectionContactRepository;
import com.rhythm.los.org.*;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Branch and officer scorecards. Each score is 0-100 from parts that are shown, so a branch or an officer can see
 * what moves it: book quality weighs most, so growth bought with bad loans does not score well.
 */
@Service
public class ScoresService {
    private final LoanApplicationRepository apps;
    private final DecisionRecordRepository decisions;
    private final DisbursementRepository disbursements;
    private final CollectionContactRepository contacts;
    private final BranchRepository branches;
    private final AppUserRepository users;
    private final LoanBookService book;
    private final DataScope scope;

    public ScoresService(LoanApplicationRepository apps, DecisionRecordRepository decisions, DisbursementRepository disbursements, CollectionContactRepository contacts,
                         BranchRepository branches, AppUserRepository users, LoanBookService book, DataScope scope) {
        this.apps = apps;
        this.decisions = decisions;
        this.disbursements = disbursements;
        this.contacts = contacts;
        this.branches = branches;
        this.users = users;
        this.book = book;
        this.scope = scope;
    }

    record Ctx(List<LoanApplication> apps, Map<Long, DecisionRecord> lastDecision, Map<Long, Disbursement> disbursed, List<LiveLoan> loans, Map<Long, List<CollectionContact>> contacts) {}

    Ctx ctx(CurrentUser u, int days) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        Set<Long> vis = scope.visibleBranches(u);
        List<LoanApplication> as = apps.findAll().stream().filter(a -> a.getCreatedAt().isAfter(since))
                .filter(a -> vis == null || (a.getBranchId() != null && vis.contains(a.getBranchId()))).toList();
        Map<Long, DecisionRecord> dec = new HashMap<>();
        for (DecisionRecord d : decisions.findAll()) dec.merge(d.getApplicationId(), d, (x, y) -> x.getId() > y.getId() ? x : y);
        Map<Long, Disbursement> dis = disbursements.findAll().stream().filter(d -> "SUCCESS".equals(d.getStatus()))
                .collect(Collectors.toMap(Disbursement::getApplicationId, d -> d, (x, y) -> x));
        Map<Long, List<CollectionContact>> cs = contacts.findAll().stream().collect(Collectors.groupingBy(CollectionContact::getLoanId));
        return new Ctx(as, dec, dis, book.visible(u), cs);
    }

    static double r2(double v) { return Math.round(v * 100) / 100.0; }
    static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    public List<Map<String, Object>> branchScores(CurrentUser u, int days) {
        Ctx c = ctx(u, days);
        Map<Long, Branch> br = branches.findAll().stream().collect(Collectors.toMap(Branch::getId, b -> b));
        Set<Long> ids = new TreeSet<>();
        c.apps().stream().map(LoanApplication::getBranchId).filter(Objects::nonNull).forEach(ids::add);
        c.loans().stream().map(LiveLoan::getBranchId).filter(Objects::nonNull).forEach(ids::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        double maxDisb = 1;
        Map<Long, Double> disbAmt = new HashMap<>();
        for (Long b : ids) {
            double amt = c.apps().stream().filter(a -> b.equals(a.getBranchId()) && c.disbursed().containsKey(a.getId())).mapToDouble(a -> c.disbursed().get(a.getId()).getGrossAmount().doubleValue()).sum();
            disbAmt.put(b, amt);
            maxDisb = Math.max(maxDisb, amt);
        }
        for (Long b : ids) {
            List<LoanApplication> as = c.apps().stream().filter(a -> b.equals(a.getBranchId())).toList();
            List<DecisionRecord> ds = as.stream().map(a -> c.lastDecision().get(a.getId())).filter(Objects::nonNull).toList();
            long approved = ds.stream().filter(d -> d.getDecision() != null && d.getDecision().startsWith("APPROVE")).count();
            List<Double> tat = as.stream().filter(a -> c.disbursed().containsKey(a.getId()))
                    .map(a -> Duration.between(a.getCreatedAt(), c.disbursed().get(a.getId()).getCreatedAt()).toHours() / 24.0).sorted().toList();
            double medianTat = tat.isEmpty() ? 0 : tat.get(tat.size() / 2);
            List<LiveLoan> ls = c.loans().stream().filter(l -> b.equals(l.getBranchId()) && "ACTIVE".equals(l.getStatus())).toList();
            double os = ls.stream().mapToDouble(l -> l.getOutstanding().doubleValue()).sum();
            double par30 = os == 0 ? 0 : ls.stream().filter(l -> l.getDpd() > 30).mapToDouble(l -> l.getOutstanding().doubleValue()).sum() / os;
            double gnpa = os == 0 ? 0 : ls.stream().filter(l -> "NPA".equals(l.getClassification())).mapToDouble(l -> l.getOutstanding().doubleValue()).sum() / os;
            long kept = ls.stream().flatMap(l -> c.contacts().getOrDefault(l.getId(), List.of()).stream()).filter(x -> "KEPT".equals(x.getPtpStatus())).count();
            long broken = ls.stream().flatMap(l -> c.contacts().getOrDefault(l.getId(), List.of()).stream()).filter(x -> "BROKEN".equals(x.getPtpStatus())).count();
            double quality = 1 - clamp01(par30 / 0.10), growth = disbAmt.get(b) / maxDisb, speed = tat.isEmpty() ? 0.5 : 1 - clamp01(medianTat / 14),
                    collect = kept + broken == 0 ? 0.5 : (double) kept / (kept + broken);
            int score = (int) Math.round(100 * (0.40 * quality + 0.25 * growth + 0.20 * speed + 0.15 * collect));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("branchId", b);
            m.put("branch", br.containsKey(b) ? br.get(b).getName() : "Branch " + b);
            m.put("score", score);
            m.put("applications", as.size());
            m.put("approvalRate", ds.isEmpty() ? null : r2((double) approved / ds.size()));
            m.put("disbursed", tat.size());
            m.put("disbursedAmount", Math.round(disbAmt.get(b)));
            m.put("medianTatDays", r2(medianTat));
            m.put("bookOutstanding", Math.round(os));
            m.put("par30", r2(par30 * 100) / 100);
            m.put("gnpa", r2(gnpa * 100) / 100);
            m.put("promiseKeptRate", kept + broken == 0 ? null : r2((double) kept / (kept + broken)));
            m.put("parts", Map.of("quality", r2(quality), "growth", r2(growth), "speed", r2(speed), "collections", r2(collect)));
            rows.add(m);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("score")).reversed());
        return rows;
    }

    public List<Map<String, Object>> officerScores(CurrentUser u, int days) {
        Ctx c = ctx(u, days);
        Map<Long, LiveLoan> loanByApp = c.loans().stream().filter(l -> l.getApplicationId() != null).collect(Collectors.toMap(LiveLoan::getApplicationId, l -> l, (x, y) -> x));
        Map<String, List<LoanApplication>> byOfficer = c.apps().stream().filter(a -> a.getCreatedBy() != null && !a.getCreatedBy().startsWith("borrower"))
                .collect(Collectors.groupingBy(LoanApplication::getCreatedBy));
        double maxAmt = 1;
        Map<String, Double> amt = new HashMap<>();
        for (var e : byOfficer.entrySet()) {
            double v = e.getValue().stream().filter(a -> c.disbursed().containsKey(a.getId())).mapToDouble(a -> c.disbursed().get(a.getId()).getGrossAmount().doubleValue()).sum();
            amt.put(e.getKey(), v);
            maxAmt = Math.max(maxAmt, v);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var e : byOfficer.entrySet()) {
            List<LoanApplication> as = e.getValue();
            long submitted = as.stream().filter(a -> !"DRAFT".equals(a.getAppState())).count();
            List<LoanApplication> dis = as.stream().filter(a -> c.disbursed().containsKey(a.getId())).toList();
            List<LiveLoan> ls = dis.stream().map(a -> loanByApp.get(a.getId())).filter(Objects::nonNull).toList();
            // early delinquency: more than 30 days late within the first three instalments
            long early = ls.stream().filter(l -> l.getMaxDpd() > 30 && l.getPaidEmis() <= 3).count();
            double avgPd = dis.stream().map(a -> c.lastDecision().get(a.getId())).filter(d -> d != null && d.getPd() != null).mapToDouble(d -> d.getPd().doubleValue()).average().orElse(0);
            long rejectedForData = as.stream().map(a -> c.lastDecision().get(a.getId())).filter(d -> d != null && d.getReasonCodes() != null && d.getReasonCodes().contains("RC-013")).count();
            double earlyRate = ls.isEmpty() ? 0 : (double) early / ls.size();
            double quality = 1 - clamp01(earlyRate / 0.10), conversion = submitted == 0 ? 0 : (double) dis.size() / submitted, volume = amt.get(e.getKey()) / maxAmt,
                    dataQuality = submitted == 0 ? 1 : 1 - clamp01((double) rejectedForData / submitted);
            int score = (int) Math.round(100 * (0.45 * quality + 0.25 * conversion + 0.15 * volume + 0.15 * dataQuality));
            AppUser user = users.findByUsername(e.getKey()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("officer", e.getKey());
            m.put("name", user == null ? e.getKey() : user.getFullName());
            m.put("role", user == null ? null : user.getRole().name());
            m.put("score", score);
            m.put("sourced", as.size());
            m.put("submitted", submitted);
            m.put("disbursed", dis.size());
            m.put("disbursedAmount", Math.round(amt.get(e.getKey())));
            m.put("avgPd", r2(avgPd * 100) / 100);
            m.put("earlyDelinquency", r2(earlyRate * 100) / 100);
            m.put("incompleteData", rejectedForData);
            m.put("parts", Map.of("quality", r2(quality), "conversion", r2(conversion), "volume", r2(volume), "dataQuality", r2(dataQuality)));
            rows.add(m);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> m) -> (Integer) m.get("score")).reversed());
        return rows;
    }
}
