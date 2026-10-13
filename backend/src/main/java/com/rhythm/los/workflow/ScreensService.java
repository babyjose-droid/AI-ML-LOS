package com.rhythm.los.workflow;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.application.StateHistory;
import com.rhythm.los.application.StateHistoryRepository;
import com.rhythm.los.common.Mask;
import com.rhythm.los.consent.ConsentService;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.disbursement.DisbursementRepository;
import com.rhythm.los.flow.AppStage;
import com.rhythm.los.flow.AppStageRepository;
import com.rhythm.los.flow.Stage;
import com.rhythm.los.flow.WorkflowDefinitions;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.integration.IntegrationLogRepository;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pipeline stage board (SCR-020), Customer 360 (SCR-031) and the Overview page (SCR-001). All respect the user's data scope. */
@Service
public class ScreensService {
    static final List<String> OPEN = List.of("ACTIVE", "REVIEW", "FAILED");
    static final Map<String, String> NAMES = Map.of("lead", "Lead & application", "kyc", "Consent & KYC", "docs", "Documents", "field", "Field visit",
            "uw", "Underwriting", "cam", "Credit memo", "sanction", "Sanction", "disb", "Disbursement");

    private final LoanApplicationRepository apps;
    private final AppStageRepository stages;
    private final WorkflowDefinitions defs;
    private final DataScope scope;
    private final DecisionRecordRepository decisions;
    private final DisbursementRepository disbursements;
    private final ConsentService consents;
    private final StateHistoryRepository history;
    private final IntegrationLogRepository logs;
    private final PolicyService policy;

    public ScreensService(LoanApplicationRepository apps, AppStageRepository stages, WorkflowDefinitions defs, DataScope scope,
                          DecisionRecordRepository decisions, DisbursementRepository disbursements, ConsentService consents,
                          StateHistoryRepository history, IntegrationLogRepository logs, PolicyService policy) {
        this.apps = apps;
        this.stages = stages;
        this.defs = defs;
        this.scope = scope;
        this.decisions = decisions;
        this.disbursements = disbursements;
        this.consents = consents;
        this.history = history;
        this.logs = logs;
        this.policy = policy;
    }

    private List<LoanApplication> visible(CurrentUser u) {
        Set<Long> v = scope.visibleBranches(u);
        return apps.findAllByOrderByIdDesc().stream().filter(a -> DataScope.canSee(v, a)).toList();
    }

    static boolean open(LoanApplication a) { return !List.of("REJECTED", "WITHDRAWN", "DISBURSED").contains(a.getAppState()); }

    /** Each open application in the column of its current stage, with SLA and breach. */
    public Map<String, Object> board(CurrentUser u, String product) {
        Map<Long, List<AppStage>> byApp = new HashMap<>();
        for (AppStage s : stages.findByStatusIn(OPEN)) byApp.computeIfAbsent(s.getApplicationId(), k -> new ArrayList<>()).add(s);
        Map<String, List<Map<String, Object>>> cols = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (String k : List.of("lead", "kyc", "docs", "field", "uw", "cam", "sanction", "disb")) { cols.put(k, new ArrayList<>()); names.put(k, NAMES.get(k)); }
        Instant now = Instant.now();
        int breached = 0;
        for (LoanApplication a : visible(u)) {
            if (!open(a) || (product != null && !product.isBlank() && !product.equals(a.getProductCode()))) continue;
            List<Stage> flow = defs.stages(a);
            List<AppStage> rows = byApp.getOrDefault(a.getId(), List.of());
            Stage cur = null;
            AppStage row = null;
            for (Stage s : flow) {
                Optional<AppStage> r = rows.stream().filter(x -> x.getStageKey().equals(s.key())).findFirst();
                if (r.isPresent()) { cur = s; row = r.get(); break; }
            }
            if (cur == null) cur = "DRAFT".equals(a.getAppState()) ? flow.get(0) : flow.get(flow.size() - 1);
            names.putIfAbsent(cur.key(), cur.name());
            cols.computeIfAbsent(cur.key(), k -> new ArrayList<>());
            boolean late = row != null && (row.getBreachedAt() != null || (row.getDueAt() != null && row.getDueAt().isBefore(now)));
            if (late) breached++;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("applicationId", a.getId());
            c.put("appNo", a.getAppNo());
            c.put("applicant", a.getApplicantName());
            c.put("product", a.getProductCode());
            c.put("amount", a.getLoanAmount());
            c.put("stage", cur.name());
            c.put("role", row != null && row.getEscalatedTo() != null ? row.getEscalatedTo() : cur.role());
            c.put("status", row == null ? a.getAppState() : row.getStatus());
            c.put("dueAt", row == null ? null : row.getDueAt());
            c.put("breached", late);
            c.put("ageHours", Duration.between(row != null && row.getStartedAt() != null ? row.getStartedAt() : a.getUpdatedAt(), now).toHours());
            cols.get(cur.key()).add(c);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        cols.forEach((k, v) -> {
            v.sort(Comparator.comparing((Map<String, Object> m) -> !(Boolean) m.get("breached")).thenComparing(m -> -((Long) m.get("ageHours"))));
            out.add(Map.of("key", k, "name", names.getOrDefault(k, k), "count", v.size(),
                    "breached", v.stream().filter(m -> (Boolean) m.get("breached")).count(), "cards", v));
        });
        return Map.of("columns", out, "breached", breached);
    }

    /** Everything about the borrower behind an application: other applications on the same PAN or mobile, loans, consents. */
    public Map<String, Object> customer(LoanApplication a, CurrentUser u) {
        List<LoanApplication> mine = visible(u).stream()
                .filter(x -> x.getPan().equalsIgnoreCase(a.getPan()) || (a.getMobile() != null && a.getMobile().equals(x.getMobile()))).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        double exposure = 0, disbursed = 0;
        for (LoanApplication x : mine) {
            var d = decisions.findFirstByApplicationIdOrderByIdDesc(x.getId()).orElse(null);
            double net = disbursements.findByApplicationIdOrderByIdAsc(x.getId()).stream().filter(y -> "SUCCESS".equals(y.getStatus()))
                    .mapToDouble(y -> y.getGrossAmount().doubleValue()).sum();
            disbursed += net;
            if ("DISBURSED".equals(x.getAppState())) exposure += net;
            else if (open(x)) exposure += x.getLoanAmount().doubleValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applicationId", x.getId()); m.put("appNo", x.getAppNo()); m.put("product", x.getProductCode()); m.put("amount", x.getLoanAmount());
            m.put("state", x.getAppState()); m.put("createdAt", x.getCreatedAt());
            m.put("decision", d == null ? null : d.getDecision()); m.put("band", d == null ? null : d.getRiskBand());
            m.put("disbursed", net);
            m.put("samePan", x.getPan().equalsIgnoreCase(a.getPan()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", a.getApplicantName());
        out.put("pan", Mask.pan(a.getPan()));
        out.put("mobile", a.getMobile());
        out.put("city", a.getCity());
        out.put("segment", a.getSegment());
        out.put("language", a.getPreferredLanguage());
        out.put("applications", rows);
        out.put("otherPansOnMobile", mine.stream().filter(x -> !x.getPan().equalsIgnoreCase(a.getPan())).map(x -> Mask.pan(x.getPan())).distinct().toList());
        out.put("disbursedTotal", Math.round(disbursed));
        out.put("exposure", Math.round(exposure));
        out.put("consents", consents.list(a.getId()));
        return out;
    }

    /** Overview (SCR-001): today's pipeline, 30-day outcomes, turnaround, SLA breaches and system health. */
    public Map<String, Object> overview(CurrentUser u) {
        Instant now = Instant.now(), d30 = now.minus(30, ChronoUnit.DAYS), d1 = now.minus(1, ChronoUnit.DAYS);
        List<LoanApplication> all = visible(u);
        Map<String, Long> byState = new LinkedHashMap<>();
        for (String s : List.of("DRAFT", "SUBMITTED", "UNDERWRITING", "SANCTIONED", "DISBURSED", "REJECTED", "WITHDRAWN"))
            byState.put(s, all.stream().filter(a -> s.equals(a.getAppState())).count());
        List<LoanApplication> recent = all.stream().filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isAfter(d30)).toList();
        long decided = 0, approved = 0, auto = 0;
        List<Double> tat = new ArrayList<>();
        double disbursed30 = 0;
        for (LoanApplication a : recent) {
            var d = decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).orElse(null);
            if (d != null && !"INCOMPLETE".equals(d.getDecision())) {
                decided++;
                if (d.getDecision().startsWith("APPROVE") || "REFER".equals(d.getDecision()) && List.of("SANCTIONED", "DISBURSED").contains(a.getAppState())) approved++;
                if (!"REFER".equals(d.getDecision())) auto++;
            }
            Instant sub = null, san = null;
            for (StateHistory h : history.findByApplicationIdOrderByIdAsc(a.getId())) {
                if (sub == null && "SUBMITTED".equals(h.getToState())) sub = h.getCreatedAt();
                if (san == null && "SANCTIONED".equals(h.getToState()) && "APP".equals(h.getDomain().name())) san = h.getCreatedAt();
            }
            if (sub != null && san != null) tat.add(Duration.between(sub, san).toMinutes() / 60.0);
            if ("DISBURSED".equals(a.getAppState()))
                disbursed30 += disbursements.findByApplicationIdOrderByIdAsc(a.getId()).stream().filter(y -> "SUCCESS".equals(y.getStatus()))
                        .mapToDouble(y -> y.getGrossAmount().doubleValue()).sum();
        }
        Collections.sort(tat);
        Set<Long> ids = new HashSet<>();
        all.forEach(a -> ids.add(a.getId()));
        long breaches = stages.findByStatusIn(OPEN).stream().filter(s -> ids.contains(s.getApplicationId())
                && (s.getBreachedAt() != null || (s.getDueAt() != null && s.getDueAt().isBefore(now)))).count();
        long awaitingRetry = DataFetchService.RETRYABLE_STATUS.stream().mapToLong(s -> logs.findByStatusOrderByIdDesc(s).size()).sum();
        long calls24 = logs.findByCreatedAtAfterOrderByIdDesc(d1).size();
        long failed24 = logs.findByCreatedAtAfterOrderByIdDesc(d1).stream().filter(l -> !List.of("SUCCESS", "RESOLVED").contains(l.getStatus())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byState", byState);
        out.put("newToday", all.stream().filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isAfter(d1)).count());
        out.put("pendingSanction", all.stream().filter(a -> a.getSanctionState().startsWith("PENDING_")).count());
        out.put("applications30d", recent.size());
        out.put("decided30d", decided);
        out.put("approvalRate30d", decided == 0 ? null : Math.round(1000.0 * approved / decided) / 1000.0);
        out.put("straightThroughRate30d", decided == 0 ? null : Math.round(1000.0 * auto / decided) / 1000.0);
        out.put("medianTatHours", tat.isEmpty() ? null : Math.round(tat.get(tat.size() / 2) * 10) / 10.0);
        out.put("disbursed30d", Math.round(disbursed30));
        out.put("slaBreaches", breaches);
        out.put("integrationsAwaitingRetry", awaitingRetry);
        out.put("integrationFailureRate24h", calls24 == 0 ? null : Math.round(1000.0 * failed24 / calls24) / 1000.0);
        out.put("policyVersion", policy.live().version());
        return out;
    }
}
