package com.rhythm.los.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.collateral.CollateralAi;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.field.PhotoHash;
import com.rhythm.los.flow.WorkflowService;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.integration.VendorModels.PayoutResult;
import com.rhythm.los.integration.Vendors;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.org.Role;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Loan plans for housing, education and farm loans: what the loan pays for (with documents read by AI), the plan checks before
 * sanction, and the tranche releases after disbursement (construction stages certified by an inspection, term fees paid to the
 * institution against its fee demand).
 */
@Service
public class PlanService {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN");
    static final Set<Role> CAPTURE = Set.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER, Role.CREDIT_MANAGER);
    static final Set<Role> CERTIFY = Set.of(Role.ADMIN, Role.FIELD_OFFICER, Role.VALUER);
    static final Set<Role> OPS = Set.of(Role.ADMIN, Role.OPERATIONS);
    static final Set<Role> EXCEPTIONS = Set.of(Role.ADMIN, Role.CREDIT_MANAGER, Role.CRO);
    static final Set<Role> MASTERS = Set.of(Role.ADMIN, Role.OPERATIONS, Role.CRO);
    static final Map<String, Set<String>> DOCS = Map.of(
            "HOUSING", Set.of("CONSTRUCTION_ESTIMATE", "STAGE_CERTIFICATE", "INSURANCE"),
            "EDUCATION", Set.of("ADMISSION_LETTER", "FEE_LETTER", "FEE_DEMAND"),
            "AGRI", Set.of("LAND_RECORD_712", "INSURANCE"),
            "RENTAL", Set.of("LEASE_DEED", "INSURANCE"));
    static final Map<String, Set<String>> FIELDS = Map.of(
            "HOUSING", Set.of("purpose", "estimate", "ownContribution", "carpetAreaSqft", "builtUpAreaSqft", "planNo", "builderId", "propertyAddress"),
            "EDUCATION", Set.of("studentName", "studentDob", "relationship", "institutionId", "course", "courseStart", "durationMonths", "admissionNo", "academicYear"),
            "AGRI", Set.of("activity", "crop", "landAreaAcres", "surveyNo", "village", "scalePerUnit", "units", "ownContribution", "harvestMonths", "insuranceRef"),
            "RENTAL", Set.of("tenantName", "tenantType", "monthlyRent", "leaseStart", "leaseEnd", "lockInMonths", "escalationPct", "escrowAccount", "propertyAddress"));

    private final LoanPlanRepository plans;
    private final LoanTrancheRepository tranches;
    private final PlanDocumentRepository documents;
    private final PlanPartyRepository parties;
    private final LoanApplicationRepository apps;
    private final ProductRepository products;
    private final StorageService storage;
    private final CollateralAi ai;
    private final WorkflowService workflow;
    private final IntegrationGateway gateway;
    private final Vendors.PayoutProvider payout;
    private final DataScope scope;
    private final AuditService audit;
    private final com.rhythm.los.sanction.SanctionRecordRepository sanctions;
    private com.rhythm.los.book.LoanBookService book;

    public PlanService(LoanPlanRepository plans, LoanTrancheRepository tranches, PlanDocumentRepository documents, PlanPartyRepository parties,
                       LoanApplicationRepository apps, ProductRepository products, StorageService storage, CollateralAi ai, WorkflowService workflow,
                       IntegrationGateway gateway, Vendors.PayoutProvider payout, DataScope scope, AuditService audit,
                       com.rhythm.los.sanction.SanctionRecordRepository sanctions) {
        this.sanctions = sanctions;
        this.plans = plans;
        this.tranches = tranches;
        this.documents = documents;
        this.parties = parties;
        this.apps = apps;
        this.products = products;
        this.storage = storage;
        this.ai = ai;
        this.workflow = workflow;
        this.gateway = gateway;
        this.payout = payout;
        this.scope = scope;
        this.audit = audit;
    }

    @Autowired
    void setBook(@Lazy com.rhythm.los.book.LoanBookService b) { this.book = b; }

    public record TrancheRequest(String label, BigDecimal amount, BigDecimal stagePct, String payeeKind, Long payeeId) {}
    public record PlanRequest(Map<String, Object> details, List<TrancheRequest> tranches) {}
    public record PartyRequest(String code, String name, String kind, String category, String accreditation, String gstin, String city,
                               String accountName, String accountNo, String ifsc, Boolean empanelled, String status, String notes) {}
    /** The first release, paid at disbursement: to whom and how much (fees come off a borrower payment, are collected separately otherwise). */
    public record FirstRelease(LoanApplication payee, double gross, double deductions, double net, String payeeName, boolean toBorrower) {}

    // ---------------------------------------------------------------- access

    LoanApplication app(Long appId, CurrentUser u) {
        LoanApplication a = apps.findById(appId).orElseThrow(() -> ApiException.notFound("Application"));
        Set<Long> vis = scope.visibleBranches(u);
        if (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId())) throw ApiException.notFound("Application");
        return a;
    }

    public String kind(LoanApplication a) { return products.findByCode(a.getProductCode()).map(Product::getPlanKind).filter(k -> !k.isBlank()).orElse(null); }
    public boolean isPlan(LoanApplication a) { return kind(a) != null; }
    public boolean hasTranches(LoanApplication a) { String k = kind(a); return ("HOUSING".equals(k) || "EDUCATION".equals(k)) && !tranches.findByApplicationIdOrderBySeqAsc(a.getId()).isEmpty(); }

    static void require(CurrentUser u, Set<Role> roles, String what) {
        if (!roles.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot " + what);
    }

    static double d(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }
    static LocalDate today() { return LocalDate.now(IST); }

    Map<String, Object> details(LoanPlan p) { return p == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.read(p.detailsJson, new TypeReference<Map<String, Object>>() {})); }

    /** The latest reading of each plan document type (for FEE_DEMAND, per term in tranche order). */
    Map<String, Map<String, Object>> docFields(Long appId) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (PlanDocument x : documents.findByApplicationIdOrderByIdAsc(appId))
            out.put(x.docType, x.fieldsJson == null ? Map.of() : Json.read(x.fieldsJson, new TypeReference<Map<String, Object>>() {}));
        return out;
    }

    PlanRules.Party party(Long id) {
        if (id == null) return null;
        return parties.findById(id).map(p -> new PlanRules.Party(p.id, p.name, p.kind, p.status, p.empanelled, p.accountNo != null && p.ifsc != null)).orElse(null);
    }

    List<PlanRules.Tranche> trancheFacts(Long appId) {
        return tranches.findByApplicationIdOrderBySeqAsc(appId).stream()
                .map(t -> new PlanRules.Tranche(t.seq, t.label, d(t.amount), t.stagePct == null ? null : d(t.stagePct), t.payeeKind, t.payeeId)).toList();
    }

    double sanctioned(LoanApplication a) {
        return sanctions.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "APPROVED").map(r -> r.getAmount().doubleValue()).orElse(a.getLoanAmount().doubleValue());
    }

    BigDecimal sanctionedOrNull(LoanApplication a) {
        return sanctions.findFirstByApplicationIdAndActionOrderByIdDesc(a.getId(), "APPROVED").map(com.rhythm.los.sanction.SanctionRecord::getAmount).orElse(null);
    }

    // ---------------------------------------------------------------- the plan

    public Map<String, Object> view(Long appId, CurrentUser u) {
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_PLAN_PRODUCT", "This product has no loan plan");
        LoanPlan p = plans.findByApplicationId(appId).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("product", a.getProductCode());
        m.put("applicant", a.getApplicantName());
        m.put("loan", a.getLoanAmount());
        m.put("tenureMonths", a.getTenureMonths());
        m.put("sanctioned", sanctionedOrNull(a));
        m.put("details", details(p));
        m.put("status", p == null ? "PENDING" : p.status);
        m.put("checks", p == null || p.checksJson == null ? List.of() : Json.read(p.checksJson, new TypeReference<Map<String, Object>>() {}).getOrDefault("checks", List.of()));
        m.put("conditions", p == null || p.checksJson == null ? List.of() : Json.read(p.checksJson, new TypeReference<Map<String, Object>>() {}).getOrDefault("conditions", List.of()));
        m.put("documents", documents.findByApplicationIdOrderByIdAsc(appId).stream().map(x -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", x.id); r.put("docType", x.docType); r.put("fileName", x.fileName); r.put("readConfidence", x.readConfidence); r.put("engine", x.engine);
            r.put("trancheSeq", x.trancheSeq); r.put("uploadedBy", x.uploadedBy); r.put("uploadedAt", x.uploadedAt);
            r.put("fields", x.fieldsJson == null ? Map.of() : Json.read(x.fieldsJson, new TypeReference<Map<String, Object>>() {}));
            return r;
        }).toList());
        m.put("tranches", trancheRows(a, kind));
        m.put("parties", parties.findAllByOrderByNameAsc().stream().filter(x -> "EDUCATION".equals(kind) ? "INSTITUTION".equals(x.kind) : "HOUSING".equals(kind) && "BUILDER".equals(x.kind)).map(this::partyRow).toList());
        m.put("docTypes", DOCS.get(kind));
        boolean open = !CLOSED.contains(a.getAppState()) && !"DISBURSED".equals(a.getAppState());
        m.put("canEdit", open && CAPTURE.contains(u.role()) && "NOT_STARTED".equals(a.getSanctionState()));
        m.put("canUpload", !CLOSED.contains(a.getAppState()) && (CAPTURE.contains(u.role()) || CERTIFY.contains(u.role())));
        m.put("canCertify", CERTIFY.contains(u.role()));
        m.put("canRelease", OPS.contains(u.role()));
        m.put("canRule", EXCEPTIONS.contains(u.role()));
        m.put("disbursed", "DISBURSED".equals(a.getAppState()));
        return m;
    }

    @Transactional
    public Map<String, Object> save(Long appId, PlanRequest r, CurrentUser u) {
        require(u, CAPTURE, "change the loan plan");
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_PLAN_PRODUCT", "This product has no loan plan");
        if (CLOSED.contains(a.getAppState())) throw ApiException.conflict("CLOSED", "The application is closed");
        if (!"NOT_STARTED".equals(a.getSanctionState())) throw ApiException.conflict("SANCTIONED", "The plan is fixed once sanction starts");
        LoanPlan p = plans.findByApplicationId(appId).orElseGet(() -> { LoanPlan n = new LoanPlan(); n.applicationId = appId; n.kind = kind; n.createdBy = u.username(); return n; });
        Map<String, Object> det = details(p);
        if (r.details() != null) r.details().forEach((k, v) -> {
            if (!FIELDS.get(kind).contains(k)) return;
            if (v == null || v.toString().isBlank()) det.remove(k); else det.put(k, v instanceof String s ? s.trim() : v);
        });
        p.detailsJson = Json.write(det);
        p.updatedAt = Instant.now();
        plans.save(p);
        if (r.tranches() != null && !"AGRI".equals(kind)) {
            tranches.deleteAll(tranches.findByApplicationIdOrderBySeqAsc(appId));
            tranches.flush();
            int seq = 0;
            for (TrancheRequest t : r.tranches()) {
                if (t.amount() == null || t.amount().signum() <= 0) continue;
                LoanTranche x = new LoanTranche();
                x.applicationId = appId;
                x.seq = ++seq;
                x.label = t.label() == null || t.label().isBlank() ? (kind.equals("EDUCATION") ? "Term " + seq + " fee" : "Stage " + seq) : t.label().trim();
                x.amount = t.amount().setScale(2, RoundingMode.HALF_UP);
                x.stagePct = t.stagePct();
                if ("EDUCATION".equals(kind)) {
                    x.payeeKind = "INSTITUTION";
                    Object inst = det.get("institutionId");
                    x.payeeId = inst == null ? null : Long.valueOf(inst.toString());
                } else {
                    x.payeeKind = t.payeeKind() == null ? "BORROWER" : t.payeeKind();
                    x.payeeId = "BORROWER".equals(x.payeeKind) ? null : t.payeeId();
                }
                tranches.save(x);
            }
        }
        evaluate(a, p);
        audit.record(u, "plan.saved", "LOAN_PLAN", a.getAppNo(), appId, kind + " plan saved · " + p.status);
        return view(appId, u);
    }

    @Transactional
    public Map<String, Object> upload(Long appId, MultipartFile file, String docType, Integer trancheSeq, CurrentUser u) throws Exception {
        if (!CAPTURE.contains(u.role()) && !CERTIFY.contains(u.role())) throw ApiException.forbidden("ROLE", "Your role cannot upload plan documents");
        LoanApplication a = app(appId, u);
        String kind = kind(a);
        if (kind == null) throw ApiException.unprocessable("NOT_PLAN_PRODUCT", "This product has no loan plan");
        if (docType == null || !DOCS.get(kind).contains(docType)) throw ApiException.unprocessable("BAD_DOC_TYPE", "For this plan upload one of " + new TreeSet<>(DOCS.get(kind)));
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "Choose a file");
        if (file.getSize() > 25L * 1024 * 1024) throw ApiException.unprocessable("TOO_LARGE", "Files must be under 25 MB");
        String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".pdf", ".jpg", ".jpeg", ".png").contains(ext)) throw ApiException.unprocessable("BAD_FILE", "Upload a PDF, JPG or PNG");
        byte[] bytes = file.getBytes();
        PlanDocument x = new PlanDocument();
        x.applicationId = appId;
        x.docType = docType;
        x.fileName = name;
        x.fileHash = PhotoHash.sha256(bytes);
        x.storagePath = storage.store(appId, bytes, ext);
        x.uploadedBy = u.username();
        x.trancheSeq = trancheSeq;
        Map<String, Object> f = new LinkedHashMap<>();
        double conf = 0;
        String engine = "MANUAL";
        Optional<Map<String, Object>> read = ai.planDocument(bytes, file.getContentType(), name, docType);
        if (read.isPresent()) {
            engine = "AI-" + read.get().getOrDefault("engine", "ocr");
            @SuppressWarnings("unchecked") Map<String, Object> raw = (Map<String, Object>) read.get().getOrDefault("fields", Map.of());
            raw.forEach((k, v) -> f.put(k, v instanceof Map<?, ?> mv ? mv.get("value") : v));
            conf = read.get().get("readConfidence") instanceof Number n ? n.doubleValue() : 0;
        }
        if ("FEE_DEMAND".equals(docType) && x.trancheSeq == null && f.get("term") instanceof Number n) x.trancheSeq = n.intValue();
        x.fieldsJson = Json.write(f);
        x.engine = engine;
        x.readConfidence = BigDecimal.valueOf(conf).setScale(3, RoundingMode.HALF_UP);
        documents.save(x);
        // prefill blanks in the plan from what was read
        LoanPlan p = plans.findByApplicationId(appId).orElseGet(() -> { LoanPlan n = new LoanPlan(); n.applicationId = appId; n.kind = kind; n.createdBy = u.username(); return n; });
        Map<String, Object> det = details(p);
        if ("NOT_STARTED".equals(a.getSanctionState())) {
            switch (docType) {
                case "CONSTRUCTION_ESTIMATE" -> { fill(det, "estimate", f.get("total")); fill(det, "carpetAreaSqft", f.get("carpetAreaSqft")); fill(det, "builtUpAreaSqft", f.get("builtUpAreaSqft")); fill(det, "planNo", f.get("planNo")); }
                case "ADMISSION_LETTER" -> { fill(det, "studentName", f.get("student")); fill(det, "course", f.get("course")); fill(det, "courseStart", f.get("startDate")); fill(det, "durationMonths", f.get("durationMonths")); fill(det, "admissionNo", f.get("admissionNo")); }
                case "FEE_LETTER" -> fill(det, "academicYear", f.get("academicYear"));
                case "LAND_RECORD_712" -> { fill(det, "surveyNo", f.get("surveyNo")); fill(det, "landAreaAcres", f.get("areaAcres")); fill(det, "village", f.get("village")); fill(det, "crop", f.get("crops")); }
                case "INSURANCE" -> { if ("AGRI".equals(kind)) fill(det, "insuranceRef", f.get("policyNo")); }
                case "LEASE_DEED" -> { fill(det, "tenantName", f.get("lessee")); fill(det, "monthlyRent", f.get("monthlyRent")); fill(det, "leaseStart", f.get("startDate"));
                    fill(det, "leaseEnd", f.get("endDate")); fill(det, "lockInMonths", f.get("lockInMonths")); fill(det, "escalationPct", f.get("escalationPct")); }
                default -> { }
            }
            p.detailsJson = Json.write(det);
        }
        plans.save(p);
        evaluate(a, p);
        audit.record(u, "plan.document_uploaded", "LOAN_PLAN", a.getAppNo(), appId, docType + " · " + engine + (f.isEmpty() ? "" : " · read " + f.keySet()));
        return view(appId, u);
    }

    static void fill(Map<String, Object> det, String key, Object v) { if (v != null && !v.toString().isBlank() && !det.containsKey(key)) det.put(key, v); }

    PlanRules.Result evaluate(LoanApplication a, LoanPlan p) {
        String kind = kind(a);
        Map<String, Object> det = details(p);
        products.findByCode(a.getProductCode()).ifPresent(pr -> det.put("_ratePa", pr.getRateMax() == null ? 0 : pr.getRateMax().doubleValue()));
        Long inst = det.get("institutionId") == null ? null : Long.valueOf(det.get("institutionId").toString());
        PlanRules.Result r = PlanRules.evaluate(kind, det, docFields(a.getId()), trancheFacts(a.getId()), party(inst), a.getApplicantName(),
                a.getLoanAmount().doubleValue(), a.getTenureMonths(), today(), rulings(p));
        String before = p.status;
        p.status = r.status();
        p.checksJson = Json.write(r);
        plans.save(p);
        if ("PASS".equals(r.status())) workflow.recordAutomatic(a, "plan", "PASS", "Loan plan checks pass");
        else if ("PASS".equals(before)) workflow.recordAutomatic(a, "plan", "FAIL", "Loan plan no longer meets the checks");
        return r;
    }

    static Map<String, Map<String, Object>> rulings(LoanPlan p) {
        return p.rulingsJson == null ? new LinkedHashMap<>() : new LinkedHashMap<>(Json.read(p.rulingsJson, new TypeReference<Map<String, Map<String, Object>>>() {}));
    }

    @Transactional
    public Map<String, Object> ruling(Long appId, String check, String note, CurrentUser u) {
        require(u, EXCEPTIONS, "approve exceptions");
        LoanApplication a = app(appId, u);
        LoanPlan p = plans.findByApplicationId(appId).orElseThrow(() -> ApiException.notFound("Plan"));
        if (check == null || PlanRules.HARD.contains(check)) throw ApiException.unprocessable("NOT_OVERRIDABLE", check + " cannot be overridden");
        if (note == null || note.trim().length() < 15) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 15 characters");
        PlanRules.Result r = evaluate(a, p);
        if (r.checks().stream().noneMatch(c -> c.id().equals(check) && "REFER".equals(c.result()))) throw ApiException.conflict("NOT_REFERRED", check + " is not referred");
        Map<String, Map<String, Object>> m = rulings(p);
        m.put(check, Map.of("by", u.username(), "note", note.trim(), "at", Instant.now().toString()));
        p.rulingsJson = Json.write(m);
        evaluate(a, p);
        audit.record(u, "plan.exception", "LOAN_PLAN", a.getAppNo(), appId, check + ": " + note.trim());
        return view(appId, u);
    }

    // ---------------------------------------------------------------- gates

    public void requireReadyForSanction(LoanApplication a, BigDecimal amount) {
        if (!isPlan(a)) return;
        LoanPlan p = plans.findByApplicationId(a.getId()).orElseThrow(() -> ApiException.conflict("PLAN_NOT_SET", "Complete the loan plan (Plan tab) before sanction"));
        PlanRules.Result r = evaluate(a, p);
        if (!"PASS".equals(r.status()))
            throw ApiException.conflict("PLAN_NOT_READY", "Loan plan " + r.status() + ": " + String.join("; ", r.checks().stream().filter(PlanRules.Check::blocks).map(c -> c.id() + " " + c.reason()).limit(4).toList()));
        List<LoanTranche> ts = tranches.findByApplicationIdOrderBySeqAsc(a.getId());
        if (amount != null && !ts.isEmpty()) {
            double sum = ts.stream().mapToDouble(t -> d(t.amount)).sum();
            if (Math.abs(sum - amount.doubleValue()) > 1)
                throw ApiException.unprocessable("TRANCHES_NOT_SANCTION", "The releases add up to " + PlanRules.rupees(sum) + "; sanction that amount or change the plan before sanction");
        }
    }

    public List<String> disbursementBlockers(LoanApplication a) {
        if (!isPlan(a)) return List.of();
        LoanPlan p = plans.findByApplicationId(a.getId()).orElse(null);
        if (p == null) return List.of("Loan plan not recorded");
        List<String> out = new ArrayList<>();
        if (!"PASS".equals(evaluate(a, p).status())) out.add("Loan plan checks not passed");
        tranches.findByApplicationIdOrderBySeqAsc(a.getId()).stream().findFirst().ifPresent(t -> {
            if (!"BORROWER".equals(t.payeeKind)) {
                PlanRules.Party py = party(t.payeeId);
                if (py == null || !"ACTIVE".equals(py.status()) || !py.hasAccount()) out.add("Payee of the first release is not approved or has no account");
            }
        });
        return out;
    }

    LoanApplication shadow(LoanApplication a, PlanParty x) {
        LoanApplication s = new LoanApplication();
        s.setApplicantName(x.accountName == null ? x.name : x.accountName);
        s.setPan(a.getPan());
        s.setAppNo(a.getAppNo());
        s.setProductCode(a.getProductCode());
        s.setBankAccountNo(x.accountNo);
        s.setBankIfsc(x.ifsc);
        return s;
    }

    /** For loans released in tranches: the first release replaces the full payout at disbursement. */
    public Optional<FirstRelease> firstRelease(LoanApplication a, Kfs kfs) {
        if (!hasTranches(a)) return Optional.empty();
        LoanTranche t = tranches.findByApplicationIdOrderBySeqAsc(a.getId()).get(0);
        double fees = kfs.processingFee() + kfs.gstOnFee() + kfs.insurance();
        if ("BORROWER".equals(t.payeeKind)) return Optional.of(new FirstRelease(a, d(t.amount), fees, Math.max(0, d(t.amount) - fees), a.getApplicantName(), true));
        PlanParty x = parties.findById(t.payeeId).orElseThrow(() -> ApiException.conflict("NO_PAYEE", "Payee not found"));
        return Optional.of(new FirstRelease(shadow(a, x), d(t.amount), 0, d(t.amount), x.name, false));
    }

    @Transactional
    public void onFirstRelease(LoanApplication a, String utr, double paid, String paidTo, String by) {
        LoanTranche t = tranches.findByApplicationIdOrderBySeqAsc(a.getId()).get(0);
        t.status = "PAID";
        t.paidAmount = money(paid);
        t.paidAt = Instant.now();
        t.paidBy = by;
        t.utr = utr;
        t.paidTo = paidTo;
        tranches.save(t);
        if (book != null) book.setDisbursed(a.getId(), d(t.amount));
        audit.record(by, "SYSTEM", "plan.tranche_released", "LOAN_TRANCHE", a.getAppNo() + "/1", a.getId(), t.label + " " + PlanRules.rupees(d(t.amount)) + " to " + paidTo + " · UTR " + utr);
    }

    // ---------------------------------------------------------------- tranches after disbursement

    PlanRules.Release releaseCheck(LoanApplication a, String kind, LoanTranche t, List<LoanTranche> all) {
        boolean earlier = all.stream().filter(x -> x.seq < t.seq).allMatch(x -> "PAID".equals(x.status));
        double before = all.stream().filter(x -> "PAID".equals(x.status)).mapToDouble(x -> d(x.amount)).sum();
        Double demand = null;
        if ("EDUCATION".equals(kind)) for (PlanDocument x : documents.findByApplicationIdOrderByIdAsc(a.getId()))
            if ("FEE_DEMAND".equals(x.docType) && Objects.equals(x.trancheSeq, t.seq) && x.fieldsJson != null) {
                Object v = Json.read(x.fieldsJson, new TypeReference<Map<String, Object>>() {}).get("amountDue");
                if (v instanceof Number n) demand = n.doubleValue();
            }
        return PlanRules.release(kind, new PlanRules.Tranche(t.seq, t.label, d(t.amount), t.stagePct == null ? null : d(t.stagePct), t.payeeKind, t.payeeId),
                earlier, t.certifiedPct == null ? null : d(t.certifiedPct), demand, before, sanctioned(a), party(t.payeeId));
    }

    List<Map<String, Object>> trancheRows(LoanApplication a, String kind) {
        List<LoanTranche> all = tranches.findByApplicationIdOrderBySeqAsc(a.getId());
        boolean disbursed = "DISBURSED".equals(a.getAppState());
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoanTranche t : all) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id); m.put("seq", t.seq); m.put("label", t.label); m.put("amount", t.amount); m.put("stagePct", t.stagePct);
            m.put("payeeKind", t.payeeKind); m.put("payee", t.payeeId == null ? null : parties.findById(t.payeeId).map(x -> x.name).orElse(null));
            m.put("status", t.status); m.put("certifiedPct", t.certifiedPct); m.put("certifiedBy", t.certifiedBy); m.put("certifiedAt", t.certifiedAt);
            m.put("certificateNote", t.certificateNote); m.put("paidAmount", t.paidAmount); m.put("paidAt", t.paidAt); m.put("paidBy", t.paidBy);
            m.put("utr", t.utr); m.put("paidTo", t.paidTo);
            if (disbursed && !"PAID".equals(t.status)) {
                PlanRules.Release r = releaseCheck(a, kind, t, all);
                m.put("release", r.status());
                m.put("releaseChecks", r.checks());
            }
            out.add(m);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> certify(Long trancheId, BigDecimal pct, String note, CurrentUser u) {
        require(u, CERTIFY, "certify construction progress");
        LoanTranche t = tranches.findById(trancheId).orElseThrow(() -> ApiException.notFound("Tranche"));
        LoanApplication a = app(t.applicationId, u);
        if (!"HOUSING".equals(kind(a))) throw ApiException.unprocessable("NOT_HOUSING", "Progress is certified for housing construction stages");
        if (!"DISBURSED".equals(a.getAppState())) throw ApiException.conflict("NOT_DISBURSED", "Inspect stages after the first release");
        if ("PAID".equals(t.status)) throw ApiException.conflict("PAID", "This stage is already released");
        if (pct == null || pct.doubleValue() <= 0 || pct.doubleValue() > 100) throw ApiException.unprocessable("BAD_PERCENT", "Progress is 1 to 100%");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("NOTE_REQUIRED", "Describe what was seen (at least 10 characters)");
        t.certifiedPct = pct.setScale(2, RoundingMode.HALF_UP);
        t.certifiedBy = u.username();
        t.certifiedAt = Instant.now();
        t.certificateNote = note.trim();
        PlanRules.Release r = releaseCheck(a, "HOUSING", t, tranches.findByApplicationIdOrderBySeqAsc(a.getId()));
        t.status = "PASS".equals(r.status()) ? "READY" : "PLANNED";
        tranches.save(t);
        audit.record(u, "plan.stage_certified", "LOAN_TRANCHE", a.getAppNo() + "/" + t.seq, a.getId(), pct.stripTrailingZeros().toPlainString() + "% · " + note.trim());
        return view(a.getId(), u);
    }

    @Transactional
    public Map<String, Object> release(Long trancheId, CurrentUser u) {
        require(u, OPS, "release tranches");
        LoanTranche t = tranches.findById(trancheId).orElseThrow(() -> ApiException.notFound("Tranche"));
        LoanApplication a = app(t.applicationId, u);
        String kind = kind(a);
        if (!"DISBURSED".equals(a.getAppState())) throw ApiException.conflict("NOT_DISBURSED", "Release later stages after disbursement");
        if ("PAID".equals(t.status)) throw ApiException.conflict("PAID", "Already released");
        if (u.username().equals(t.certifiedBy)) throw ApiException.forbidden("MAKER_CHECKER", "The inspector cannot also release the money");
        PlanRules.Release r = releaseCheck(a, kind, t, tranches.findByApplicationIdOrderBySeqAsc(a.getId()));
        if (!"PASS".equals(r.status()))
            throw ApiException.conflict("RELEASE_" + r.status(), "Release checks " + r.status() + ": " + String.join("; ", r.checks().stream().filter(c -> !"PASS".equals(c.result())).map(c -> c.id() + " " + c.reason()).toList()));
        LoanApplication payee = a;
        String to = a.getApplicantName();
        if (!"BORROWER".equals(t.payeeKind)) {
            PlanParty x = parties.findById(t.payeeId).orElseThrow();
            payee = shadow(a, x);
            to = x.name;
        }
        final LoanApplication pay = payee;
        double amount = d(t.amount);
        String ref = a.getAppNo() + "-T" + t.seq;
        var po = gateway.call(a.getId(), payout.name(), "payout.transfer", () -> payout.pay(pay, amount, ref), p -> p.success() ? "UTR " + p.utr() : "failed " + p.failureReason(), null);
        if (!po.ok() || !po.value().success()) throw ApiException.conflict("PAYOUT_FAILED", "Payout failed: " + (po.ok() ? po.value().failureReason() : po.error()));
        PayoutResult p = po.value();
        t.status = "PAID";
        t.paidAmount = money(amount);
        t.paidAt = Instant.now();
        t.paidBy = u.username();
        t.utr = p.utr();
        t.paidTo = to;
        tranches.save(t);
        double released = tranches.findByApplicationIdOrderBySeqAsc(a.getId()).stream().filter(x -> "PAID".equals(x.status)).mapToDouble(x -> d(x.amount)).sum();
        if (book != null) book.setDisbursed(a.getId(), released);
        audit.record(u, "plan.tranche_released", "LOAN_TRANCHE", a.getAppNo() + "/" + t.seq, a.getId(), t.label + " " + PlanRules.rupees(amount) + " to " + to + " · UTR " + p.utr()
                + " · released " + PlanRules.rupees(released) + " of " + PlanRules.rupees(sanctioned(a)));
        return view(a.getId(), u);
    }

    public byte[] content(Long docId, CurrentUser u) {
        PlanDocument x = documents.findById(docId).orElseThrow(() -> ApiException.notFound("Document"));
        app(x.applicationId, u);
        return storage.read(x.storagePath);
    }

    /** For My work: stages to inspect (field officers, valuers) and releases to make or fee demands to collect (operations). */
    public List<Map<String, Object>> tasks(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        boolean insp = CERTIFY.contains(u.role()), ops = OPS.contains(u.role());
        if (!insp && !ops) return out;
        Set<Long> vis = scope.visibleBranches(u);
        for (LoanPlan p : plans.findAllByOrderByIdDesc()) {
            if (!Set.of("HOUSING", "EDUCATION").contains(p.kind)) continue;
            LoanApplication a = apps.findById(p.applicationId).orElse(null);
            if (a == null || !"DISBURSED".equals(a.getAppState()) || (vis != null && a.getBranchId() != null && !vis.contains(a.getBranchId()))) continue;
            List<LoanTranche> all = tranches.findByApplicationIdOrderBySeqAsc(a.getId());
            LoanTranche next = all.stream().filter(x -> !"PAID".equals(x.status)).findFirst().orElse(null);
            if (next == null) continue;
            PlanRules.Release r = releaseCheck(a, p.kind, next, all);
            String task = null;
            if ("PASS".equals(r.status()) && ops) task = "Release " + next.label + ": " + PlanRules.rupees(d(next.amount));
            else if ("HOUSING".equals(p.kind) && insp && next.certifiedPct == null) task = "Inspect the construction for " + next.label + " (needs " + (next.stagePct == null ? 0 : next.stagePct.stripTrailingZeros().toPlainString()) + "%)";
            else if ("EDUCATION".equals(p.kind) && ops && !"PASS".equals(r.status())) task = "Collect the fee demand for " + next.label;
            if (task == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("application", a);
            m.put("task", task);
            m.put("ageHours", Duration.between(a.getUpdatedAt(), Instant.now()).toHours());
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------- institutions and builders

    Map<String, Object> partyRow(PlanParty p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id); m.put("code", p.code); m.put("name", p.name); m.put("kind", p.kind); m.put("category", p.category); m.put("accreditation", p.accreditation);
        m.put("gstin", p.gstin); m.put("city", p.city); m.put("accountName", p.accountName); m.put("ifsc", p.ifsc);
        m.put("account", p.accountNo == null ? null : "X".repeat(Math.max(0, p.accountNo.length() - 4)) + p.accountNo.substring(Math.max(0, p.accountNo.length() - 4)));
        m.put("empanelled", p.empanelled); m.put("status", p.status); m.put("notes", p.notes);
        m.put("paid", tranches.findByPayeeId(p.id).stream().filter(t -> "PAID".equals(t.status)).mapToDouble(t -> d(t.amount)).sum());
        m.put("releases", tranches.findByPayeeId(p.id).size());
        return m;
    }

    public List<Map<String, Object>> parties() { return parties.findAllByOrderByNameAsc().stream().map(this::partyRow).toList(); }

    @Transactional
    public Map<String, Object> saveParty(Long id, PartyRequest r, CurrentUser u) {
        require(u, MASTERS, "change institutions and builders");
        PlanParty p = id == null ? new PlanParty() : parties.findById(id).orElseThrow(() -> ApiException.notFound("Institution or builder"));
        if (id == null) {
            if (r.code() == null || !r.code().matches("[A-Z0-9-]{3,20}")) throw ApiException.unprocessable("BAD_CODE", "Code: 3 to 20 capitals, digits or -");
            if (parties.findByCode(r.code()).isPresent()) throw ApiException.conflict("DUPLICATE", "Code " + r.code() + " exists");
            p.code = r.code();
            p.createdBy = u.username();
        }
        if (r.name() != null) p.name = r.name().trim();
        if (p.name == null || p.name.isBlank()) throw ApiException.unprocessable("NAME_REQUIRED", "Enter the name");
        if (r.kind() != null) p.kind = r.kind();
        if (!Set.of("INSTITUTION", "BUILDER").contains(String.valueOf(p.kind))) throw ApiException.unprocessable("BAD_KIND", "Kind is INSTITUTION or BUILDER");
        if (r.category() != null) p.category = r.category().isBlank() ? null : r.category();
        if (r.accreditation() != null) p.accreditation = r.accreditation().isBlank() ? null : r.accreditation();
        if (r.gstin() != null) {
            String g = r.gstin().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
            if (!g.isEmpty() && !g.matches("\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]")) throw ApiException.unprocessable("BAD_GSTIN", "Enter a valid GSTIN");
            p.gstin = g.isEmpty() ? null : g;
        }
        if (r.city() != null) p.city = r.city();
        if (r.accountName() != null) p.accountName = r.accountName().isBlank() ? null : r.accountName();
        if (r.accountNo() != null && !r.accountNo().isBlank()) p.accountNo = r.accountNo().replaceAll("\\s", "");
        if (r.ifsc() != null) {
            String ifsc = r.ifsc().trim().toUpperCase(Locale.ROOT);
            if (!ifsc.isEmpty() && !ifsc.matches("[A-Z]{4}0[A-Z0-9]{6}")) throw ApiException.unprocessable("BAD_IFSC", "IFSC is 4 letters, 0, then 6 letters or digits");
            p.ifsc = ifsc.isEmpty() ? null : ifsc;
        }
        if (r.empanelled() != null) p.empanelled = r.empanelled();
        if (r.status() != null) p.status = r.status();
        if (r.notes() != null) p.notes = r.notes();
        p.updatedAt = Instant.now();
        parties.save(p);
        audit.record(u, id == null ? "plan.party_created" : "plan.party_updated", "PLAN_PARTY", p.code, null, p.name + " · " + p.kind + " · " + p.status);
        return partyRow(p);
    }
}
