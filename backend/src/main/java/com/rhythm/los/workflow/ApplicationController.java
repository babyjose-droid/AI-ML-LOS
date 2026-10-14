package com.rhythm.los.workflow;

import com.rhythm.los.application.*;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.decision.DecisionRecord;
import com.rhythm.los.decision.DecisionService;
import com.rhythm.los.decision.FraudReviewService;
import com.rhythm.los.disbursement.Disbursement;
import com.rhythm.los.disbursement.DisbursementService;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.document.KycCheck;
import com.rhythm.los.document.KycCheckRepository;
import com.rhythm.los.integration.DataFetchService;
import com.rhythm.los.integration.DataSnapshot;
import com.rhythm.los.integration.DataSnapshotRepository;
import com.rhythm.los.integration.IntegrationLog;
import com.rhythm.los.integration.IntegrationLogRepository;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.SanctionRecord;
import com.rhythm.los.sanction.SanctionRecordRepository;
import com.rhythm.los.sanction.SanctionService;
import com.rhythm.los.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/applications")
public class ApplicationController {
    private static final String FRONT = "hasAnyRole('SALES','OPERATIONS','ADMIN')";
    private static final String OPS = "hasAnyRole('OPERATIONS','ADMIN')";
    /** Any staff role reaches the endpoint; the workflow decides per application who may act (StageAccess). */
    private static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";
    private static final String CREDIT = "hasAnyRole('CREDIT_OFFICER','CREDIT_MANAGER','CRO')";

    private final ApplicationService apps;
    private final LoanApplicationRepository repo;
    private final DataFetchService data;
    private final DocumentService docs;
    private final DecisionService decisions;
    private final FraudReviewService fraud;
    private final SanctionService sanctions;
    private final SanctionRecordRepository sanctionRecords;
    private final DisbursementService disbursements;
    private final ProcessService process;
    private final DataSnapshotRepository snapshots;
    private final IntegrationLogRepository logs;
    private final KycCheckRepository kycChecks;
    private final com.rhythm.los.flow.StageAccess access;
    private final com.rhythm.los.org.DataScope scope;
    private final com.rhythm.los.consent.ConsentService consentService;
    @org.springframework.beans.factory.annotation.Autowired private DataCaptureService dataCapture;
    @org.springframework.beans.factory.annotation.Autowired private ScreensService screens;

    public ApplicationController(KycCheckRepository kycChecks, ApplicationService apps, LoanApplicationRepository repo, DataFetchService data, DocumentService docs,
                                 DecisionService decisions, FraudReviewService fraud, SanctionService sanctions,
                                 SanctionRecordRepository sanctionRecords, DisbursementService disbursements,
                                 ProcessService process, DataSnapshotRepository snapshots, IntegrationLogRepository logs, com.rhythm.los.flow.StageAccess access,
                                 com.rhythm.los.org.DataScope scope, com.rhythm.los.consent.ConsentService consentService) {
        this.access = access;
        this.scope = scope;
        this.consentService = consentService;
        this.apps = apps;
        this.repo = repo;
        this.data = data;
        this.docs = docs;
        this.decisions = decisions;
        this.fraud = fraud;
        this.sanctions = sanctions;
        this.sanctionRecords = sanctionRecords;
        this.disbursements = disbursements;
        this.process = process;
        this.snapshots = snapshots;
        this.logs = logs;
        this.kycChecks = kycChecks;
    }

    public record NoteRequest(String note) {}
    public record ResolveRequest(boolean verified, String note) {}
    public record FraudRequest(boolean clear, String note) {}
    public record SanctionRequest(BigDecimal amount, String note, List<String> conditions) {}
    public record OtpRequest(String otp, String language) {}

    // ---- queries ----

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String state, @RequestParam(required = false) String q) {
        String needle = q == null ? null : q.trim().toLowerCase();
        java.util.Set<Long> visible = scope.visibleBranches(CurrentUser.get());
        return repo.findAllByOrderByIdDesc().stream()
                .filter(a -> com.rhythm.los.org.DataScope.canSee(visible, a))
                .filter(a -> state == null || state.isBlank() || state.equals(a.getAppState()))
                .filter(a -> needle == null || needle.isEmpty() || a.getApplicantName().toLowerCase().contains(needle)
                        || a.getPan().toLowerCase().contains(needle) || (a.getAppNo() != null && a.getAppNo().toLowerCase().contains(needle)))
                .map(this::summary).toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> view(@PathVariable Long id) {
        LoanApplication a = apps.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("application", a);
        m.put("states", a.states());
        m.put("actions", ActionPolicy.actions(a, CurrentUser.get(), access));
        m.put("blockers", decisions.blockers(a));
        m.put("missingDocuments", docs.missing(a));
        m.put("product", apps.product(a));
        m.put("documentAi", docs.aiEnabled());
        return m;
    }

    /** The draft's own values for the edit form, including PAN and account number in full (drafts only). */
    @GetMapping("/{id}/draft")
    @PreAuthorize(FRONT)
    public Map<String, Object> draft(@PathVariable Long id) {
        LoanApplication a = apps.get(id);
        if (!"DRAFT".equals(a.getAppState())) throw ApiException.conflict("NOT_EDITABLE", "Only draft applications can be edited");
        Map<String, Object> m = new LinkedHashMap<>(com.rhythm.los.common.Json.read(com.rhythm.los.common.Json.write(a), Map.class));
        m.put("pan", a.getPan());
        m.put("bankAccountNo", a.getBankAccountNo());
        return m;
    }

    @GetMapping("/{id}/customer")
    @PreAuthorize(STAFF)
    public Map<String, Object> customer(@PathVariable Long id) { return screens.customer(apps.get(id), CurrentUser.get()); }

    @GetMapping("/{id}/data-capture")
    @PreAuthorize(STAFF)
    public List<DataCaptureService.Item> dataCapture(@PathVariable Long id) { return dataCapture.checklist(id); }

    @GetMapping("/{id}/consents")
    public List<com.rhythm.los.consent.Consent> consents(@PathVariable Long id) { return consentService.list(id); }

    /** Records the borrower's withdrawal of one consent (FR-KYC-02): further pulls stop and the effect is recorded. */
    @PostMapping("/{id}/consents/{source}/withdraw")
    @PreAuthorize(FRONT)
    public com.rhythm.los.consent.Consent withdrawConsent(@PathVariable Long id, @PathVariable String source, @RequestBody NoteRequest r) {
        return consentService.withdraw(apps.get(id), source, r.note(), CurrentUser.get());
    }

    @GetMapping("/{id}/history")
    public List<StateHistory> history(@PathVariable Long id) { return apps.history(id); }

    @GetMapping("/{id}/snapshots")
    public List<DataSnapshot> snapshots(@PathVariable Long id) { return snapshots.findByApplicationIdOrderByIdDesc(id); }

    @GetMapping("/{id}/integrations")
    public List<IntegrationLog> integrations(@PathVariable Long id) { return logs.findByApplicationIdOrderByIdDesc(id); }

    @GetMapping("/{id}/decision")
    public ResponseEntity<DecisionRecord> decision(@PathVariable Long id) {
        return decisions.latest(id).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}/sanctions")
    public List<SanctionRecord> sanctionHistory(@PathVariable Long id) { return sanctionRecords.findByApplicationIdOrderByIdAsc(id); }

    @GetMapping("/{id}/sanction-letter")
    @PreAuthorize(STAFF)
    public Map<String, Object> letter(@PathVariable Long id, @RequestParam(required = false) String lang) { return sanctions.letter(id, lang); }

    @GetMapping("/{id}/kfs")
    public Kfs kfs(@PathVariable Long id) { return sanctions.kfs(id); }

    @GetMapping("/{id}/disbursements")
    public List<Disbursement> disbursementList(@PathVariable Long id) { return disbursements.list(id); }

    @GetMapping("/{id}/kyc-checks")
    public List<KycCheck> kycChecks(@PathVariable Long id) { return kycChecks.findByApplicationIdOrderByIdAsc(id); }

    @PostMapping("/{id}/documents/{docId}/review")
    @PreAuthorize(STAFF)
    public AppDocument reviewDocument(@PathVariable Long id, @PathVariable Long docId, @RequestBody DocumentService.ReviewRequest r) {
        access.require(apps.get(id), CurrentUser.get(), "docs");
        AppDocument d = docs.review(id, docId, r, CurrentUser.get());
        process.continueAfterPersonStep(id);
        return d;
    }

    @GetMapping("/{id}/documents")
    public List<AppDocument> documents(@PathVariable Long id) { return docs.list(id); }

    @GetMapping(value = "/{id}/documents/{docId}/analysis", produces = MediaType.APPLICATION_JSON_VALUE)
    public String documentAnalysis(@PathVariable Long id, @PathVariable Long docId) {
        AppDocument d = docs.get(docId);
        if (!d.getApplicationId().equals(id) || d.getAnalysisJson() == null) throw ApiException.notFound("Statement analysis");
        return d.getAnalysisJson();
    }

    @GetMapping("/{id}/documents/{docId}/content")
    public ResponseEntity<byte[]> documentContent(@PathVariable Long id, @PathVariable Long docId) {
        AppDocument d = docs.get(docId);
        if (!d.getApplicationId().equals(id)) throw ApiException.notFound("Document");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + d.getFileName().replace("\"", "") + "\"")
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .body(docs.content(d));
    }

    // ---- origination ----

    @PostMapping
    @PreAuthorize(FRONT)
    public LoanApplication create(@Valid @RequestBody ApplicationRequest r) { return apps.create(r, CurrentUser.get()); }

    @PutMapping("/{id}")
    @PreAuthorize(FRONT)
    public LoanApplication update(@PathVariable Long id, @Valid @RequestBody ApplicationRequest r) { return apps.update(id, r, CurrentUser.get()); }

    @PostMapping("/{id}/submit")
    @PreAuthorize(FRONT)
    public LoanApplication submit(@PathVariable Long id) { return apps.submit(id, CurrentUser.get()); }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize(FRONT)
    public LoanApplication withdraw(@PathVariable Long id, @RequestBody NoteRequest r) { return apps.withdraw(id, r.note(), CurrentUser.get()); }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(FRONT)
    public AppDocument upload(@PathVariable Long id, @RequestParam String type, @RequestParam MultipartFile file,
                              @RequestParam(required = false) String password) {
        return docs.upload(id, type, file, password, CurrentUser.get());
    }

    // ---- processing (by the roles the product's workflow assigns) ----

    @PostMapping("/{id}/process")
    @PreAuthorize(STAFF)
    public Map<String, Object> process(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "kyc", "docs");
        var steps = process.process(id, CurrentUser.get());
        return Map.of("steps", steps, "view", view(id));
    }

    @PostMapping("/{id}/kyc/run")
    @PreAuthorize(STAFF)
    public LoanApplication runKyc(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "kyc");
        return data.runKyc(id, CurrentUser.get());
    }

    @PostMapping("/{id}/kyc/resolve")
    @PreAuthorize(STAFF)
    public LoanApplication resolveKyc(@PathVariable Long id, @RequestBody ResolveRequest r) {
        access.require(apps.get(id), CurrentUser.get(), "kyc");
        data.resolveKyc(id, r.verified(), r.note(), CurrentUser.get());
        process.continueAfterPersonStep(id);
        return apps.get(id);
    }

    @PostMapping("/{id}/data/fetch")
    @PreAuthorize(STAFF)
    public LoanApplication fetch(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "kyc", "docs", "uw");
        return data.fetchData(id, CurrentUser.get());
    }

    @PostMapping("/{id}/documents/verify")
    @PreAuthorize(STAFF)
    public LoanApplication verifyDocs(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "docs");
        return docs.verify(id, CurrentUser.get());
    }

    @PostMapping("/{id}/field-visit")
    @PreAuthorize(STAFF)
    public LoanApplication fieldVisit(@PathVariable Long id, @RequestBody NoteRequest r) {
        access.require(apps.get(id), CurrentUser.get(), "field");
        apps.recordFieldVisit(id, r.note(), CurrentUser.get());
        process.continueAfterPersonStep(id);
        return apps.get(id);
    }

    @PostMapping("/{id}/decision/run")
    @PreAuthorize(STAFF)
    public DecisionRecord runDecision(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "kyc", "docs", "uw");
        return decisions.run(id, CurrentUser.get());
    }

    // ---- risk and credit ----

    @PostMapping("/{id}/fraud/disposition")
    @PreAuthorize("hasRole('FRAUD_ANALYST')")
    public LoanApplication fraudDisposition(@PathVariable Long id, @RequestBody FraudRequest r) {
        return fraud.dispose(id, r.clear(), r.note(), CurrentUser.get());
    }

    @PostMapping("/{id}/sanction")
    @PreAuthorize(CREDIT)
    public SanctionRecord sanction(@PathVariable Long id, @RequestBody SanctionRequest r) {
        return sanctions.approve(id, r.amount(), r.note(), r.conditions(), CurrentUser.get());
    }

    @PostMapping("/{id}/sanction/decline")
    @PreAuthorize(CREDIT)
    public SanctionRecord decline(@PathVariable Long id, @RequestBody NoteRequest r) { return sanctions.decline(id, r.note(), CurrentUser.get()); }

    @PostMapping("/{id}/sanction/send-back")
    @PreAuthorize(CREDIT)
    public SanctionRecord sendBack(@PathVariable Long id, @RequestBody NoteRequest r) { return sanctions.sendBack(id, r.note(), CurrentUser.get()); }

    @PostMapping("/{id}/sanction/escalate")
    @PreAuthorize(CREDIT)
    public SanctionRecord escalate(@PathVariable Long id, @RequestBody NoteRequest r) { return sanctions.escalate(id, r.note(), CurrentUser.get()); }

    @PostMapping("/{id}/kfs/accept")
    @PreAuthorize(FRONT)
    public LoanApplication acceptKfs(@PathVariable Long id, @RequestBody OtpRequest r) { return sanctions.acceptKfs(id, r.otp(), r.language(), CurrentUser.get()); }

    // ---- disbursement ----

    @PostMapping("/{id}/disburse")
    @PreAuthorize(STAFF)
    public Disbursement disburse(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "disb");
        return disbursements.disburse(id, CurrentUser.get());
    }

    @PostMapping("/{id}/disburse/retry")
    @PreAuthorize(STAFF)
    public LoanApplication retryDisbursement(@PathVariable Long id) {
        access.require(apps.get(id), CurrentUser.get(), "disb");
        return disbursements.retry(id, CurrentUser.get());
    }

    private Map<String, Object> summary(LoanApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appNo", a.getAppNo());
        m.put("applicantName", a.getApplicantName());
        m.put("pan", com.rhythm.los.common.Mask.pan(a.getPan()));
        m.put("productCode", a.getProductCode());
        m.put("workflowVersion", a.getWorkflowVersion());
        m.put("segment", a.getSegment());
        m.put("loanAmount", a.getLoanAmount());
        m.put("tenureMonths", a.getTenureMonths());
        m.put("city", a.getCity());
        m.put("states", a.states());
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt());
        m.put("updatedAt", a.getUpdatedAt());
        return m;
    }
}
