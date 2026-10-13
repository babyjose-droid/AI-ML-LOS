package com.rhythm.los.memo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionRecordRepository;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.flow.StageAccess;
import com.rhythm.los.flow.WorkflowService;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Credit memo (FSD 12.2): the engine drafts it from the decision; a credit officer edits it and justifies each deviation;
 * on sign-off it is locked and versioned (FR-CAM-03). Numbers that do not appear in the file are flagged (FR-CAM-02).
 * When the product's workflow has the credit memo stage in "automatic, person confirms" mode, signing is that
 * confirmation, and sanction waits for it.
 */
@Service
public class CreditMemoService {
    static final Set<Role> CREDIT = EnumSet.of(Role.CREDIT_OFFICER, Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    public record Deviation(String rule, String description, String justification) {}

    private final CreditMemoRepository repo;
    private final DecisionRecordRepository decisions;
    private final ApplicationService apps;
    private final StageAccess access;
    private final WorkflowService workflow;
    private final AuditService audit;

    public CreditMemoService(CreditMemoRepository repo, DecisionRecordRepository decisions, ApplicationService apps, StageAccess access,
                             WorkflowService workflow, AuditService audit) {
        this.repo = repo;
        this.decisions = decisions;
        this.apps = apps;
        this.access = access;
        this.workflow = workflow;
        this.audit = audit;
    }

    /** The memo for the latest decision; a new decision starts a new draft from the engine's text. */
    @Transactional
    public Optional<CreditMemo> current(Long appId) {
        DecisionRecord d = decisions.findFirstByApplicationIdOrderByIdDesc(appId).orElse(null);
        if (d == null || d.getCreditMemo() == null || "INCOMPLETE".equals(d.getDecision())) return Optional.empty();
        CreditMemo last = repo.findFirstByApplicationIdOrderByVersionDesc(appId).orElse(null);
        if (last != null && last.getDecisionId().equals(d.getId())) return Optional.of(last);
        if (last != null && "DRAFT".equals(last.getStatus())) { last.setStatus("SUPERSEDED"); repo.save(last); }
        CreditMemo m = new CreditMemo();
        m.setApplicationId(appId);
        m.setDecisionId(d.getId());
        m.setVersion(last == null ? 1 : last.getVersion() + 1);
        m.setStatus("DRAFT");
        m.setBody(d.getCreditMemo());
        m.setDeviationsJson(Json.write(deviations(d)));
        m.setEditedBy("system");
        return Optional.of(repo.save(m));
    }

    /** Policy rules the case fails that a person may approve (REFER rules): each needs a written justification. */
    static List<Deviation> deviations(DecisionRecord d) {
        List<EngineResult.RuleResult> rules = Json.read(d.getRulesJson(), new TypeReference<List<EngineResult.RuleResult>>() {});
        List<Deviation> out = new ArrayList<>();
        for (EngineResult.RuleResult r : rules) if (!r.pass() && "REFER".equals(r.action())) out.add(new Deviation(r.id(), r.description(), ""));
        return out;
    }

    public Map<String, Object> view(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        Optional<CreditMemo> cur = current(appId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("memo", cur.orElse(null));
        m.put("deviations", cur.map(x -> Json.read(x.getDeviationsJson() == null ? "[]" : x.getDeviationsJson(), new TypeReference<List<Deviation>>() {})).orElse(List.of()));
        m.put("history", repo.findByApplicationIdOrderByVersionDesc(appId).stream().map(x -> Map.of("version", x.getVersion(), "status", x.getStatus(),
                "editedBy", x.getEditedBy(), "editedAt", x.getEditedAt(), "signedBy", x.getSignedBy() == null ? "" : x.getSignedBy())).toList());
        boolean open = cur.isPresent() && "DRAFT".equals(cur.get().getStatus()) && ApplicationService.isOpen(a);
        m.put("canEdit", open && mayWork(a, by));
        m.put("signRequired", workflow.memoSignRequired(a));
        m.put("unsourcedNumbers", cur.map(x -> unsourced(appId, x)).orElse(List.of()));
        return m;
    }

    private boolean mayWork(LoanApplication a, CurrentUser by) {
        return CREDIT.contains(by.role()) || access.can(a, "cam", by);
    }

    public record EditRequest(String body, List<Deviation> deviations) {}

    @Transactional
    public Map<String, Object> edit(Long appId, EditRequest req, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        CreditMemo cur = current(appId).orElseThrow(() -> ApiException.conflict("NO_MEMO", "There is no credit memo yet: run the decision engine first"));
        if (!mayWork(a, by)) throw ApiException.forbidden("NOT_ALLOWED", "Only credit staff can edit the credit memo");
        if (!"DRAFT".equals(cur.getStatus())) throw ApiException.conflict("LOCKED", "The memo is signed and locked; a new decision starts a new draft");
        if (req.body() == null || req.body().trim().length() < 50) throw ApiException.unprocessable("MEMO_TOO_SHORT", "The memo needs at least 50 characters");
        cur.setStatus("SUPERSEDED");
        repo.save(cur);
        CreditMemo m = new CreditMemo();
        m.setApplicationId(appId);
        m.setDecisionId(cur.getDecisionId());
        m.setVersion(cur.getVersion() + 1);
        m.setStatus("DRAFT");
        m.setBody(req.body().trim());
        m.setDeviationsJson(Json.write(mergeDeviations(cur, req.deviations())));
        m.setEditedBy(by.username());
        m.setEditedAt(Instant.now());
        repo.save(m);
        audit.record(by, "memo.edited", "APPLICATION", a.getAppNo(), appId, "Credit memo v" + m.getVersion());
        return view(appId, by);
    }

    @Transactional
    public Map<String, Object> sign(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        CreditMemo cur = current(appId).orElseThrow(() -> ApiException.conflict("NO_MEMO", "There is no credit memo yet"));
        if (!mayWork(a, by)) throw ApiException.forbidden("NOT_ALLOWED", "Only credit staff can sign the credit memo");
        if (!"DRAFT".equals(cur.getStatus())) throw ApiException.conflict("LOCKED", "The memo is already signed");
        List<Deviation> devs = Json.read(cur.getDeviationsJson() == null ? "[]" : cur.getDeviationsJson(), new TypeReference<List<Deviation>>() {});
        List<String> missing = devs.stream().filter(d -> d.justification() == null || d.justification().trim().length() < 10).map(Deviation::rule).toList();
        if (!missing.isEmpty()) throw ApiException.unprocessable("JUSTIFY_DEVIATIONS", "Write a justification (at least 10 characters) for: " + String.join(", ", missing));
        cur.setStatus("SIGNED");
        cur.setSignedBy(by.username());
        cur.setSignerRole(by.role().name());
        cur.setSignedAt(Instant.now());
        repo.save(cur);
        audit.record(by, "memo.signed", "APPLICATION", a.getAppNo(), appId, "Credit memo v" + cur.getVersion() + " signed and locked");
        workflow.markReviewed(a, "cam", by, "Credit memo v" + cur.getVersion() + " signed");
        return view(appId, by);
    }

    private static List<Deviation> mergeDeviations(CreditMemo cur, List<Deviation> edits) {
        List<Deviation> base = Json.read(cur.getDeviationsJson() == null ? "[]" : cur.getDeviationsJson(), new TypeReference<List<Deviation>>() {});
        if (edits == null) return base;
        Map<String, String> j = new HashMap<>();
        for (Deviation d : edits) if (d.rule() != null) j.put(d.rule(), d.justification());
        return base.stream().map(d -> new Deviation(d.rule(), d.description(), j.getOrDefault(d.rule(), d.justification()))).toList();
    }

    /** Numbers in the memo that are not in the engine's draft or the decision record (FR-CAM-02). */
    List<String> unsourced(Long appId, CreditMemo m) {
        DecisionRecord d = decisions.findById(m.getDecisionId()).orElse(null);
        if (d == null) return List.of();
        String file = (d.getCreditMemo() == null ? "" : d.getCreditMemo()) + " " + Json.write(d);
        Set<String> known = new HashSet<>();
        Matcher f = NUMBER.matcher(file);
        while (f.find()) known.add(f.group().replace(",", ""));
        Set<String> out = new TreeSet<>();
        Matcher x = NUMBER.matcher(m.getBody());
        while (x.find()) {
            String n = x.group().replace(",", "");
            if (n.length() >= 3 && !known.contains(n)) out.add(x.group());
        }
        return new ArrayList<>(out);
    }
}
