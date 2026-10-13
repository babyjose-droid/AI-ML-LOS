package com.rhythm.los.flow;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.org.Role;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Workflow designer: per-product stage sequences, edited as drafts, checked, sent for approval and published by the
 * CRO (maker-checker). Applications already in flight keep the version they started on.
 */
@Service
public class WorkflowDesignService {
    static final Set<Role> MAKERS = EnumSet.of(Role.ADMIN, Role.CREDIT_MANAGER);
    static final Set<String> ON_FAILURE = Set.of("QUEUE", "ESCALATE", "REJECT", "PAUSE");
    static final Set<String> STAFF_ROLES = Set.of("SALES", "OPERATIONS", "FIELD_OFFICER", "CREDIT_OFFICER", "CREDIT_MANAGER", "CRO", "FRAUD_ANALYST", "COMPLIANCE");
    static final Set<String> NO_SKIP = Set.of("lead", "kyc", "uw", "sanction", "disb");

    private final WorkflowVersionRepository repo;
    private final ProductRepository products;
    private final AuditService audit;

    public WorkflowDesignService(WorkflowVersionRepository repo, ProductRepository products, AuditService audit) {
        this.repo = repo;
        this.products = products;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- read

    public List<Map<String, Object>> overview() {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<WorkflowVersion>> byProduct = new LinkedHashMap<>();
        for (WorkflowVersion v : repo.findAllByOrderByProductCodeAscVersionDesc()) byProduct.computeIfAbsent(v.getProductCode(), k -> new ArrayList<>()).add(v);
        for (var p : products.findAll()) {
            List<WorkflowVersion> vs = byProduct.getOrDefault(p.getCode(), List.of());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("productCode", p.getCode());
            m.put("productName", p.getName());
            m.put("segment", p.getSegment());
            m.put("published", vs.stream().filter(v -> "PUBLISHED".equals(v.getStatus())).findFirst().map(this::summary).orElse(null));
            m.put("versions", vs.stream().map(this::summary).toList());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> (String) m.get("productCode")));
        return out;
    }

    private Map<String, Object> summary(WorkflowVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("version", v.getVersion());
        m.put("status", v.getStatus());
        m.put("stageCount", WorkflowDefinitions.parse(v.getStagesJson()).size());
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt());
        m.put("approvedBy", v.getApprovedBy());
        m.put("approvedAt", v.getApprovedAt());
        m.put("notes", v.getNotes());
        return m;
    }

    public Map<String, Object> detail(Long id) {
        WorkflowVersion v = get(id);
        List<Stage> stages = WorkflowDefinitions.parse(v.getStagesJson());
        Map<String, Object> m = new LinkedHashMap<>(summary(v));
        m.put("productCode", v.getProductCode());
        m.put("stages", stages);
        m.put("issues", validate(stages));
        m.put("submittedAt", v.getSubmittedAt());
        m.put("decisionNote", v.getDecisionNote());
        repo.findFirstByProductCodeAndStatusOrderByVersionDesc(v.getProductCode(), "PUBLISHED")
                .filter(p -> !p.getId().equals(v.getId()))
                .ifPresent(p -> m.put("changes", diff(WorkflowDefinitions.parse(p.getStagesJson()), stages)));
        return m;
    }

    public List<Map<String, Object>> library() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StageLibrary.Def d : StageLibrary.DEFS.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.key());
            m.put("name", d.name());
            m.put("role", d.role());
            m.put("sla", d.sla());
            m.put("mode", d.mode());
            m.put("modes", d.modes());
            m.put("kind", d.kind().name());
            m.put("integrations", d.integrations());
            m.put("dataFields", d.dataFields());
            m.put("help", d.help());
            m.put("mandatory", NO_SKIP.contains(d.key()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> conditionFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fields", Conditions.FIELDS);
        m.put("examples", List.of("segment = SALARIED AND amount <= 5,00,000 AND incomeVerifiedViaAA = true",
                "decision = APPROVE AND amount <= 2L", "existingCustomer = true"));
        return m;
    }

    WorkflowVersion get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("Workflow version " + id)); }

    // ---------------------------------------------------------------- validation

    /** Problems that stop a version from being sent for approval. Empty means it can be published. */
    public static List<String> validate(List<Stage> stages) {
        List<String> e = new ArrayList<>();
        if (stages == null || stages.isEmpty()) return List.of("Add at least the mandatory stages");
        List<String> keys = stages.stream().map(Stage::key).toList();
        Set<String> seen = new HashSet<>();
        for (String k : keys) {
            if (!StageLibrary.DEFS.containsKey(k)) e.add("Unknown stage \"" + k + "\"");
            else if (!seen.add(k)) e.add(StageLibrary.def(k).name() + " appears more than once");
        }
        if (!"lead".equals(keys.get(0))) e.add("The workflow must start with Lead & application");
        if (!"disb".equals(keys.get(keys.size() - 1))) e.add("The workflow must end with Disbursement");
        for (String m : List.of("kyc", "uw", "sanction")) if (!keys.contains(m)) e.add(StageLibrary.def(m).name() + " is mandatory");
        order(keys, "kyc", "uw", e);
        order(keys, "docs", "uw", e);
        order(keys, "uw", "sanction", e);
        order(keys, "cam", "sanction", e);
        order(keys, "uw", "cam", e);
        order(keys, "cgt", "grt", e);
        for (String pre : List.of("field", "cgt", "grt", "val", "gold", "legal")) order(keys, pre, "sanction", e);
        for (Stage s : stages) {
            StageLibrary.Def d = StageLibrary.DEFS.get(s.key());
            if (d == null) continue;
            String n = d.name();
            if (s.name() == null || s.name().isBlank()) e.add(n + ": give the stage a name");
            if (s.sla() < 1 || s.sla() > 720) e.add(n + ": SLA must be between 1 and 720 hours");
            if (!d.modes().contains(s.mode())) e.add(n + ": mode must be one of " + String.join(", ", d.modes()));
            if (!STAFF_ROLES.contains(s.role())) e.add(n + ": choose a staff role");
            if (!ON_FAILURE.contains(s.onFailure())) e.add(n + ": on failure must be one of " + String.join(", ", new TreeSet<>(ON_FAILURE)));
            if (s.skip() != null && !s.skip().isBlank()) {
                if (NO_SKIP.contains(s.key())) e.add(n + " is mandatory and cannot have a skip rule");
                else if (s.skip().length() > 250) e.add(n + ": skip rule is too long (250 characters at most)");
                else {
                    String err = Conditions.validate(s.skip());
                    if (err != null) e.add(n + " skip rule: " + err);
                }
            }
        }
        return e;
    }

    private static void order(List<String> keys, String a, String b, List<String> e) {
        int i = keys.indexOf(a), j = keys.indexOf(b);
        if (i >= 0 && j >= 0 && i > j) e.add(StageLibrary.def(a).name() + " must come before " + StageLibrary.def(b).name());
    }

    static List<String> diff(List<Stage> from, List<Stage> to) {
        List<String> out = new ArrayList<>();
        Map<String, Stage> a = new LinkedHashMap<>();
        for (Stage s : from) a.put(s.key(), s);
        Map<String, Stage> b = new LinkedHashMap<>();
        for (Stage s : to) b.put(s.key(), s);
        for (String k : b.keySet()) if (!a.containsKey(k)) out.add("Added " + b.get(k).name());
        for (String k : a.keySet()) if (!b.containsKey(k)) out.add("Removed " + a.get(k).name());
        List<String> ka = from.stream().map(Stage::key).filter(b::containsKey).toList();
        List<String> kb = to.stream().map(Stage::key).filter(a::containsKey).toList();
        if (!ka.equals(kb)) out.add("Stage order changed");
        for (String k : b.keySet()) {
            Stage x = a.get(k), y = b.get(k);
            if (x == null) continue;
            if (!Objects.equals(x.role(), y.role())) out.add(y.name() + ": role " + x.role() + " → " + y.role());
            if (x.sla() != y.sla()) out.add(y.name() + ": SLA " + x.sla() + " h → " + y.sla() + " h");
            if (!Objects.equals(x.mode(), y.mode())) out.add(y.name() + ": mode " + x.mode() + " → " + y.mode());
            if (!Objects.equals(x.onFailure(), y.onFailure())) out.add(y.name() + ": on failure " + x.onFailure() + " → " + y.onFailure());
            if (!Objects.equals(blank(x.skip()), blank(y.skip()))) out.add(y.name() + ": skip rule \"" + blank(x.skip()) + "\" → \"" + blank(y.skip()) + "\"");
            if (!Objects.equals(x.name(), y.name())) out.add("Renamed " + x.name() + " → " + y.name());
        }
        return out;
    }

    private static String blank(String s) { return s == null ? "" : s.trim(); }

    // ---------------------------------------------------------------- maker-checker

    private void requireMaker(CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Only an admin or credit manager can edit workflows");
    }

    /** New draft for a product, copied from the published version (or the default sequence). */
    @Transactional
    public WorkflowVersion createDraft(String productCode, CurrentUser by) {
        requireMaker(by);
        products.findByCode(productCode).orElseThrow(() -> ApiException.notFound("Product " + productCode));
        for (WorkflowVersion v : repo.findByProductCodeOrderByVersionDesc(productCode)) {
            if (Set.of("DRAFT", "PENDING_APPROVAL").contains(v.getStatus()))
                throw ApiException.conflict("DRAFT_EXISTS", productCode + " already has version " + v.getVersion() + " " + v.getStatus().toLowerCase().replace('_', ' ') + "; finish or delete it first");
        }
        List<Stage> base = repo.findFirstByProductCodeAndStatusOrderByVersionDesc(productCode, "PUBLISHED")
                .map(p -> WorkflowDefinitions.parse(p.getStagesJson()))
                .orElseGet(() -> WorkflowDefinitions.DEFAULT.stream().map(StageLibrary::defaults).toList());
        int next = repo.findByProductCodeOrderByVersionDesc(productCode).stream().mapToInt(WorkflowVersion::getVersion).max().orElse(0) + 1;
        WorkflowVersion v = new WorkflowVersion();
        v.setProductCode(productCode);
        v.setVersion(next);
        v.setStatus("DRAFT");
        v.setStagesJson(Json.write(base));
        v.setCreatedBy(by.username());
        repo.save(v);
        audit.record(by, "workflow.draft_created", "WORKFLOW", productCode + " v" + next, null, null);
        return v;
    }

    public record UpdateRequest(List<Stage> stages, String notes) {}

    @Transactional
    public Map<String, Object> update(Long id, UpdateRequest req, CurrentUser by) {
        requireMaker(by);
        WorkflowVersion v = get(id);
        if (!"DRAFT".equals(v.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Only drafts can be edited (this is " + v.getStatus() + ")");
        if (req.stages() == null || req.stages().size() > 30) throw ApiException.unprocessable("BAD_STAGES", "Between 1 and 30 stages");
        List<Stage> clean = req.stages().stream().map(s -> new Stage(s.key(), s.name() == null ? null : s.name().trim(), s.role(), s.sla(),
                s.mode(), s.onFailure() == null ? "QUEUE" : s.onFailure(), s.skip() == null ? "" : s.skip().trim(),
                s.integrations() == null ? List.of() : s.integrations())).toList();
        v.setStagesJson(Json.write(clean));
        if (req.notes() != null) v.setNotes(req.notes().length() > 1000 ? req.notes().substring(0, 1000) : req.notes());
        repo.save(v);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> submit(Long id, CurrentUser by) {
        requireMaker(by);
        WorkflowVersion v = get(id);
        if (!"DRAFT".equals(v.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Version is " + v.getStatus());
        List<String> issues = validate(WorkflowDefinitions.parse(v.getStagesJson()));
        if (!issues.isEmpty()) throw ApiException.unprocessable("WORKFLOW_INVALID", "Fix before sending: " + String.join("; ", issues));
        if (v.getNotes() == null || v.getNotes().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Describe the change in at least 10 characters");
        v.setStatus("PENDING_APPROVAL");
        v.setSubmittedAt(Instant.now());
        repo.save(v);
        audit.record(by, "workflow.submitted", "WORKFLOW", v.getProductCode() + " v" + v.getVersion(), null, v.getNotes());
        return detail(id);
    }

    public record Decision(boolean approve, String note) {}

    @Transactional
    public Map<String, Object> decide(Long id, Decision req, CurrentUser by) {
        WorkflowVersion v = get(id);
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can approve a workflow");
        if (!"PENDING_APPROVAL".equals(v.getStatus())) throw ApiException.conflict("NOT_PENDING", "Version is " + v.getStatus());
        if (by.username().equals(v.getCreatedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who made a workflow cannot approve it");
        String note = req.note() == null ? "" : req.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        v.setApprovedBy(by.username());
        v.setApprovedAt(Instant.now());
        v.setDecisionNote(note.length() > 1000 ? note.substring(0, 1000) : note);
        if (req.approve()) {
            repo.findFirstByProductCodeAndStatusOrderByVersionDesc(v.getProductCode(), "PUBLISHED").ifPresent(old -> { old.setStatus("RETIRED"); repo.save(old); });
            v.setStatus("PUBLISHED");
            audit.record(by, "workflow.published", "WORKFLOW", v.getProductCode() + " v" + v.getVersion(), null, note);
        } else {
            v.setStatus("REJECTED");
            audit.record(by, "workflow.rejected", "WORKFLOW", v.getProductCode() + " v" + v.getVersion(), null, note);
        }
        repo.save(v);
        return detail(id);
    }

    @Transactional
    public void deleteDraft(Long id, CurrentUser by) {
        requireMaker(by);
        WorkflowVersion v = get(id);
        if (!Set.of("DRAFT", "REJECTED").contains(v.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Only drafts and rejected versions can be deleted");
        repo.delete(v);
        audit.record(by, "workflow.draft_deleted", "WORKFLOW", v.getProductCode() + " v" + v.getVersion(), null, null);
    }

    /** Versions waiting for the CRO (for My work). */
    public List<WorkflowVersion> pending() {
        return repo.findAllByOrderByProductCodeAscVersionDesc().stream().filter(v -> "PENDING_APPROVAL".equals(v.getStatus())).toList();
    }
}
