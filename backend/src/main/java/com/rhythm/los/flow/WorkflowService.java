package com.rhythm.los.flow;

import com.rhythm.los.application.ApplicationChanged;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.integration.DataSnapshotRepository;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Runs each application through its product's workflow: works out which stage is active, starts its SLA clock,
 * skips stages whose rule applies, records stages a person completes, gates the decision, sanction and disbursement
 * on unfinished stages, and acts when a stage fails or breaches its SLA.
 */
@Service
public class WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);
    static final Set<String> OPEN_ROW = Set.of("ACTIVE", "REVIEW", "FAILED");
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);

    private final WorkflowDefinitions defs;
    private final AppStageRepository rows;
    private final LoanApplicationRepository appRepo;
    private final ApplicationService apps;
    private final DecisionRecordRepository decisions;
    private final DataSnapshotRepository snapshots;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public WorkflowService(WorkflowDefinitions defs, AppStageRepository rows, LoanApplicationRepository appRepo, ApplicationService apps,
                           DecisionRecordRepository decisions, DataSnapshotRepository snapshots, AuditService audit, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.defs = defs;
        this.rows = rows;
        this.appRepo = appRepo;
        this.apps = apps;
        this.decisions = decisions;
        this.snapshots = snapshots;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- progress

    @EventListener
    public void onChange(ApplicationChanged e) {
        appRepo.findById(e.applicationId()).ifPresent(a -> refresh(a, e.actor()));
    }

    /** Recomputes the stages of one application. Safe to call often. */
    @Transactional
    public void refresh(LoanApplication a) { refresh(a, null); }

    /** As refresh, recording who caused the change on stages it completes (the person whose action finished them). */
    @Transactional
    public void refresh(LoanApplication a, String actor) {
        if (BUSY.get()) return;
        BUSY.set(true);
        try {
            doRefresh(a, actor == null ? "system" : actor);
        } finally {
            BUSY.set(false);
        }
    }

    private void doRefresh(LoanApplication a, String actor) {
        List<Stage> stages = defs.stages(a);
        // one request at a time per application: take the application's row lock before touching its stages, the same
        // lock any state change takes, so two concurrent requests queue instead of deadlocking. NO KEY: inserts that
        // reference the application (integration logs in their own transaction) must not wait for it
        jdbc.queryForList("select id from loan_application where id = ? for no key update", a.getId());
        // create missing rows first; two requests on the same application at once must not both insert
        for (Stage s : stages)
            jdbc.update("insert into app_stage (application_id, stage_key, status) values (?, ?, 'PENDING') on conflict (application_id, stage_key) do nothing",
                    a.getId(), s.key());
        Map<String, AppStage> byKey = new HashMap<>();
        for (AppStage r : rows.findByApplicationId(a.getId())) byKey.put(r.getStageKey(), r);
        boolean closed = Set.of("REJECTED", "WITHDRAWN").contains(a.getAppState());
        Map<String, Object> facts = null;
        boolean stateActive = false, personOpen = false;
        Instant now = Instant.now();
        for (Stage s : stages) {
            StageLibrary.Def d = StageLibrary.def(s.key());
            AppStage r = byKey.get(s.key());
            if (r != null && Set.of("DONE", "SKIPPED").contains(r.getStatus())) continue;
            boolean done = d.kind() == StageLibrary.Kind.RECORDED
                    ? r != null && "PASS".equals(r.getOutcome())
                    : d.done().test(a) && (!"AUTO_REVIEW".equals(s.mode()) || r != null && r.getReviewedAt() != null);
            if (done && "field".equals(s.key()) && "NOT_REQUIRED".equals(a.getFieldState())) {
                r = row(byKey, a, s);
                r.setStatus("SKIPPED");
                r.setSkipReason(s.skip() != null && !s.skip().isBlank() ? s.skip() : "Not required by the product's field visit rule");
                if (r.getCompletedAt() == null) r.setCompletedAt(now);
                rows.save(r);
                continue;
            }
            if (done) {
                r = row(byKey, a, s);
                r.setStatus("DONE");
                if (r.getCompletedAt() == null) r.setCompletedAt(now);
                if (r.getCompletedBy() == null) r.setCompletedBy(actor);
                rows.save(r);
                continue;
            }
            if (closed) {
                if (r != null && OPEN_ROW.contains(r.getStatus())) { r.setStatus("CLOSED"); rows.save(r); }
                continue;
            }
            // stages a person records run as soon as earlier person stages are finished (in parallel with system
            // stages); system stages run one at a time and wait for any person stage before them
            boolean recorded = d.kind() == StageLibrary.Kind.RECORDED;
            boolean personStage = recorded || "AUTO_REVIEW".equals(s.mode());
            boolean eligible = recorded ? !personOpen : !stateActive && !personOpen;
            if (eligible && s.skip() != null && !s.skip().isBlank()) {
                if (facts == null) facts = facts(a);
                if (Conditions.eval(s.skip(), facts)) {
                    r = row(byKey, a, s);
                    r.setStatus("SKIPPED");
                    r.setSkipReason(s.skip());
                    r.setCompletedAt(now);
                    rows.save(r);
                    audit.record(CurrentUser.SYSTEM, "workflow.stage_skipped", "APPLICATION", a.getAppNo(), a.getId(), s.name() + " skipped: " + s.skip());
                    continue;
                }
            }
            if (eligible) {
                if (!recorded) stateActive = true;
                r = row(byKey, a, s);
                String st = "FAIL".equals(r.getOutcome()) ? "FAILED"
                        : !recorded && "AUTO_REVIEW".equals(s.mode()) && d.done().test(a) ? "REVIEW" : "ACTIVE";
                r.setStatus(st);
                if (r.getStartedAt() == null) {
                    r.setStartedAt(now);
                    r.setDueAt(calendar == null ? now.plus(Duration.ofHours(Math.max(1, s.sla()))) : calendar.due(now, Math.max(1, s.sla()), a.getBranchId()));
                }
                rows.save(r);
            } else if (r != null && OPEN_ROW.contains(r.getStatus()) && !"FAILED".equals(r.getStatus())) {
                r.setStatus("PENDING");
                rows.save(r);
            }
            if (personStage) personOpen = true;
        }
    }

    private AppStage row(Map<String, AppStage> byKey, LoanApplication a, Stage s) {
        return byKey.computeIfAbsent(s.key(), k -> new AppStage(a.getId(), k));
    }

    /** Facts for skip rules, including the decision once there is one. */
    public Map<String, Object> facts(LoanApplication a) {
        boolean existing = a.getPan() != null && appRepo.countDisbursedByPanExcluding(a.getPan(), a.getId()) > 0;
        Map<String, Object> f = WorkflowDefinitions.basicFacts(a, existing);
        decisions.findFirstByApplicationIdOrderByIdDesc(a.getId()).ifPresent((DecisionRecord d) -> {
            f.put("decision", d.getDecision());
            f.put("pd", d.getPd() == null ? null : d.getPd().doubleValue());
            f.put("band", d.getRiskBand());
            f.put("score", (double) d.getScore());
        });
        snapshots.findFirstByApplicationIdAndKindOrderByIdDesc(a.getId(), "AA").ifPresent(s -> {
            boolean stmt = "STATEMENT-AI".equals(s.getVendor());
            f.put("incomeVerifiedViaStatement", stmt);
            f.put("incomeVerifiedViaAA", !stmt);
        });
        return f;
    }

    /** Stages before the gate that a person still has to finish. Used to hold the decision, sanction and disbursement. */
    @Transactional
    public List<String> pendingBefore(LoanApplication a, String gateKey) {
        refresh(a);
        List<Stage> stages = defs.stages(a);
        Map<String, AppStage> byKey = new HashMap<>();
        for (AppStage r : rows.findByApplicationId(a.getId())) byKey.put(r.getStageKey(), r);
        List<String> out = new ArrayList<>();
        for (Stage s : stages) {
            if (s.key().equals(gateKey)) break;
            StageLibrary.Def d = StageLibrary.def(s.key());
            boolean needsPerson = d.kind() == StageLibrary.Kind.RECORDED || "AUTO_REVIEW".equals(s.mode());
            if (!needsPerson) continue;
            AppStage r = byKey.get(s.key());
            if (r == null || !Set.of("DONE", "SKIPPED").contains(r.getStatus()))
                out.add("Workflow stage \"" + s.name() + "\" not completed" + (r != null && "FAILED".equals(r.getStatus()) ? " (failed, waiting for " + label(r.getEscalatedTo() == null ? s.role() : r.getEscalatedTo()) + ")" : ""));
        }
        return out;
    }

    /** Signing the credit memo is the person's confirmation when the memo stage is "automatic, person confirms" and not skipped. */
    private com.rhythm.los.masters.BusinessCalendar calendar;

    @org.springframework.beans.factory.annotation.Autowired
    void setCalendar(com.rhythm.los.masters.BusinessCalendar c) { this.calendar = c; }

    public boolean memoSignRequired(LoanApplication a) {
        Stage cam = defs.stages(a).stream().filter(s -> "cam".equals(s.key())).findFirst().orElse(null);
        if (cam == null || !"AUTO_REVIEW".equals(cam.mode())) return false;
        return rows.findByApplicationIdAndStageKey(a.getId(), "cam").map(r -> !"SKIPPED".equals(r.getStatus())).orElse(true);
    }

    /** Records a person's confirmation of a stage done elsewhere (the credit memo sign-off confirms the memo stage). */
    @Transactional
    public void markReviewed(LoanApplication a, String key, CurrentUser by, String note) {
        refresh(a);
        rows.findByApplicationIdAndStageKey(a.getId(), key).ifPresent(r -> {
            if (r.getReviewedAt() != null) return;
            r.setReviewedBy(by.username());
            r.setReviewedAt(Instant.now());
            r.setNote(note);
            rows.save(r);
        });
        refresh(a, by.username());
    }

    /** True when the applicant's joint liability group passed its recognition test (GRT) in this application's workflow. */
    public boolean groupRecognised(LoanApplication a) {
        return a.getId() != null && rows.findByApplicationIdAndStageKey(a.getId(), "grt").map(r -> "PASS".equals(r.getOutcome())).orElse(false);
    }

    public void requireNothingPendingBefore(LoanApplication a, String gateKey) {
        List<String> p = pendingBefore(a, gateKey);
        if (!p.isEmpty()) throw ApiException.conflict("WORKFLOW_PENDING", String.join("; ", p));
    }

    // ---------------------------------------------------------------- view and actions

    @Transactional
    public Map<String, Object> view(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        refresh(a);
        List<Stage> stages = defs.stages(a);
        Map<String, AppStage> byKey = new HashMap<>();
        for (AppStage r : rows.findByApplicationId(appId)) byKey.put(r.getStageKey(), r);
        List<Map<String, Object>> list = new ArrayList<>();
        boolean earlierPersonStageOpen = false;
        for (Stage s : stages) {
            StageLibrary.Def d = StageLibrary.def(s.key());
            AppStage r = byKey.get(s.key());
            String status = r == null ? "PENDING" : r.getStatus();
            boolean recorded = d.kind() == StageLibrary.Kind.RECORDED;
            boolean mine = by.role() == Role.ADMIN || s.role().equals(by.role().name()) || r != null && by.role().name().equals(r.getEscalatedTo());
            boolean open = ApplicationService.isOpen(a);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("name", s.name());
            m.put("role", s.role());
            m.put("sla", s.sla());
            m.put("mode", s.mode());
            m.put("onFailure", s.onFailure());
            m.put("skip", s.skip());
            m.put("kind", d.kind().name());
            m.put("help", d.help());
            m.put("dataFields", d.dataFields());
            m.put("status", status);
            if (r != null) {
                m.put("startedAt", r.getStartedAt());
                m.put("dueAt", r.getDueAt());
                m.put("completedAt", r.getCompletedAt());
                m.put("completedBy", r.getCompletedBy());
                m.put("outcome", r.getOutcome());
                m.put("note", r.getNote());
                m.put("data", r.getDataJson() == null ? null : Json.read(r.getDataJson(), Map.class));
                m.put("skipReason", r.getSkipReason());
                m.put("breachedAt", r.getBreachedAt());
                m.put("breachAction", r.getBreachAction());
                m.put("escalatedTo", r.getEscalatedTo());
                m.put("reviewedBy", r.getReviewedBy());
            }
            m.put("canComplete", open && recorded && mine && !Set.of("DONE", "SKIPPED", "CLOSED").contains(status) && !earlierPersonStageOpen);
            m.put("canReview", open && "REVIEW".equals(status) && mine);
            m.put("assignedToMe", open && mine && by.role() != Role.ADMIN && Set.of("ACTIVE", "REVIEW", "FAILED").contains(status));
            list.add(m);
            if ((recorded || "AUTO_REVIEW".equals(s.mode())) && !Set.of("DONE", "SKIPPED").contains(status)) earlierPersonStageOpen = true;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("product", a.getProductCode());
        out.put("version", a.getWorkflowVersion());
        out.put("stages", list);
        return out;
    }

    public record CompleteRequest(String outcome, String note, Map<String, Object> data) {}

    /** A person completes a recorded stage (group formation, valuation, appraisal, legal) with PASS or FAIL. */
    @Transactional
    public Map<String, Object> complete(Long appId, String key, CompleteRequest req, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a)) throw ApiException.conflict("CLOSED", "Application is " + a.getAppState());
        Map<String, Object> v = view(appId, by);
        @SuppressWarnings("unchecked") Map<String, Object> st = ((List<Map<String, Object>>) v.get("stages")).stream()
                .filter(x -> key.equals(x.get("key"))).findFirst().orElseThrow(() -> ApiException.notFound("Stage " + key + " in this workflow"));
        if (!"RECORDED".equals(st.get("kind"))) throw ApiException.unprocessable("NOT_RECORDED", st.get("name") + " is completed by the system, not by hand");
        if (!Boolean.TRUE.equals(st.get("canComplete")))
            throw ApiException.forbidden("NOT_YOURS", st.get("name") + " is " + String.valueOf(st.get("status")).toLowerCase() + "; it is done by " + label((String) st.get("role")) + " once earlier stages are finished");
        String outcome = req.outcome() == null ? "" : req.outcome().toUpperCase();
        if (!Set.of("PASS", "FAIL").contains(outcome)) throw ApiException.unprocessable("BAD_OUTCOME", "Outcome must be PASS or FAIL");
        String note = req.note() == null ? "" : req.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Write notes of at least 10 characters");
        Map<String, Object> data = new LinkedHashMap<>();
        @SuppressWarnings("unchecked") List<String> allowed = (List<String>) st.get("dataFields");
        if (req.data() != null) req.data().forEach((k, x) -> { if (allowed.contains(k) && x != null && !String.valueOf(x).isBlank()) data.put(k, x); });
        AppStage r = rows.findByApplicationIdAndStageKey(appId, key).orElseGet(() -> new AppStage(appId, key));
        if (r.getStartedAt() == null) r.setStartedAt(Instant.now());
        r.setOutcome(outcome);
        r.setNote(note.length() > 1000 ? note.substring(0, 1000) : note);
        r.setDataJson(data.isEmpty() ? null : Json.write(data));
        r.setCompletedBy(by.username());
        r.setCompletedAt(Instant.now());
        r.setStatus("PASS".equals(outcome) ? "DONE" : "FAILED");
        rows.save(r);
        audit.record(by, "workflow.stage_" + outcome.toLowerCase(), "APPLICATION", a.getAppNo(), appId, st.get("name") + ": " + note + (data.isEmpty() ? "" : " · " + data));
        if ("FAIL".equals(outcome)) {
            Stage s = defs.stages(a).stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
            act(a, s, r, s.onFailure(), st.get("name") + " failed: " + note, by);
        }
        refresh(a);
        return view(appId, by);
    }

    /** A person confirms a stage set to AUTO_REVIEW after the system finished it. */
    @Transactional
    public Map<String, Object> review(Long appId, String key, String note, CurrentUser by) {
        Map<String, Object> v = view(appId, by);
        @SuppressWarnings("unchecked") Map<String, Object> st = ((List<Map<String, Object>>) v.get("stages")).stream()
                .filter(x -> key.equals(x.get("key"))).findFirst().orElseThrow(() -> ApiException.notFound("Stage " + key + " in this workflow"));
        if ("cam".equals(key)) throw ApiException.conflict("SIGN_MEMO", "Confirm the credit memo by signing it on the Credit memo tab");
        if (!Boolean.TRUE.equals(st.get("canReview"))) throw ApiException.forbidden("NOT_YOURS", st.get("name") + " is not waiting for your review");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Write review notes of at least 10 characters");
        AppStage r = rows.findByApplicationIdAndStageKey(appId, key).orElseThrow();
        r.setReviewedBy(by.username());
        r.setReviewedAt(Instant.now());
        r.setNote(note.trim());
        rows.save(r);
        LoanApplication a = apps.get(appId);
        audit.record(by, "workflow.stage_reviewed", "APPLICATION", a.getAppNo(), appId, st.get("name") + ": " + note.trim());
        refresh(a);
        return view(appId, by);
    }

    /** What happens when a stage fails or breaches its SLA. */
    void act(LoanApplication a, Stage s, AppStage r, String action, String reason, CurrentUser by) {
        r.setBreachAction(action);
        switch (action) {
            case "REJECT" -> {
                rows.save(r);
                if (ApplicationService.isOpen(a)) apps.reject(a, reason, by);
            }
            case "ESCALATE" -> {
                r.setEscalatedTo(StageLibrary.escalationRole(s.role()));
                rows.save(r);
                audit.record(by, "workflow.escalated", "APPLICATION", a.getAppNo(), a.getId(), reason + " · escalated to " + label(r.getEscalatedTo()));
            }
            case "PAUSE" -> {
                rows.save(r);
                // the SMS or WhatsApp message itself is sent once the messaging integration is live
                audit.record(by, "workflow.paused_borrower_notified", "APPLICATION", a.getAppNo(), a.getId(), reason + " · borrower to be notified");
            }
            default -> rows.save(r); // QUEUE: stays with the assigned role, flagged
        }
    }

    /** Marks stages past their SLA and applies the stage's failure action. Runs every minute. */
    @Scheduled(fixedDelayString = "${rhythm.workflow.sla-check-ms:60000}", initialDelayString = "${rhythm.workflow.sla-initial-delay-ms:60000}")
    @Transactional
    public int slaSweep() {
        int n = 0;
        Instant now = Instant.now();
        for (String status : List.of("ACTIVE", "REVIEW")) {
            for (AppStage r : rows.findByStatusAndDueAtBefore(status, now)) {
                if (r.getBreachedAt() != null) continue;
                LoanApplication a = appRepo.findById(r.getApplicationId()).orElse(null);
                if (a == null || !ApplicationService.isOpen(a)) continue;
                Stage s = defs.stages(a).stream().filter(x -> x.key().equals(r.getStageKey())).findFirst().orElse(null);
                if (s == null) continue;
                r.setBreachedAt(now);
                rows.save(r);
                audit.record(CurrentUser.SYSTEM, "workflow.sla_breached", "APPLICATION", a.getAppNo(), a.getId(),
                        s.name() + " not finished within " + s.sla() + " h · action " + s.onFailure());
                act(a, s, r, s.onFailure(), s.name() + " not finished within its SLA of " + s.sla() + " hours", CurrentUser.SYSTEM);
                n++;
            }
        }
        if (n > 0) log.info("Workflow SLA check: {} stage(s) breached", n);
        return n;
    }

    /** Open stages for My work: stages a person must do, plus escalations and breaches. */
    public List<Map<String, Object>> tasks(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AppStage r : rows.findByStatusIn(List.of("ACTIVE", "REVIEW", "FAILED"))) {
            LoanApplication a = appRepo.findById(r.getApplicationId()).orElse(null);
            if (a == null || !ApplicationService.isOpen(a)) continue;
            Stage s = defs.stages(a).stream().filter(x -> x.key().equals(r.getStageKey())).findFirst().orElse(null);
            if (s == null) continue;
            StageLibrary.Def d = StageLibrary.def(s.key());
            boolean personStage = d.kind() == StageLibrary.Kind.RECORDED || "REVIEW".equals(r.getStatus());
            String role = u.role().name();
            boolean forMe = personStage && (role.equals(s.role()) && r.getEscalatedTo() == null || role.equals(r.getEscalatedTo()))
                    || !personStage && role.equals(r.getEscalatedTo());
            if (!forMe) continue;
            String what = "FAILED".equals(r.getStatus()) ? s.name() + " failed: decide next step"
                    : "REVIEW".equals(r.getStatus()) ? s.name() + ": review and confirm"
                    : personStage ? s.name() : s.name() + " overdue (escalated)";
            if (r.getBreachedAt() != null) what += " · SLA breached";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("application", a);
            m.put("task", what);
            m.put("ageHours", r.getStartedAt() == null ? 0 : Duration.between(r.getStartedAt(), Instant.now()).toHours());
            m.put("slaHours", s.sla());
            out.add(m);
        }
        return out;
    }

    static String label(String role) {
        return role == null ? "" : role.replace('_', ' ').toLowerCase();
    }
}
