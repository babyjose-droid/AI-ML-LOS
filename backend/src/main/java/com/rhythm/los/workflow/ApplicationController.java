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

    public ApplicationController(KycCheckRepository kycChecks, ApplicationService apps, LoanApplicationRepository repo, DataFetchService data, DocumentService docs,
                                 DecisionService decisions, FraudReviewService fraud, SanctionService sanctions,
                                 SanctionRecordRepository sanctionRecords, DisbursementService disbursements,
                                 ProcessService process, DataSnapshotRepository snapshots, IntegrationLogRepository logs) {
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
    public record SanctionRequest(BigDecimal amount, String note) {}
    public record OtpRequest(String otp) {}

    // ---- queries ----

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) String state, @RequestParam(required = false) String q) {
        String needle = q == null ? null : q.trim().toLowerCase();
        return repo.findAllByOrderByIdDesc().stream()
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
        m.put("actions", ActionPolicy.actions(a, CurrentUser.get()));
        m.put("blockers", decisions.blockers(a));
        m.put("missingDocuments", docs.missing(a));
        m.put("product", apps.product(a));
        m.put("documentAi", docs.aiEnabled());
        return m;
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

    @GetMapping("/{id}/kfs")
    public Kfs kfs(@PathVariable Long id) { return sanctions.kfs(id); }

    @GetMapping("/{id}/disbursements")
    public List<Disbursement> disbursementList(@PathVariable Long id) { return disbursements.list(id); }

    @GetMapping("/{id}/kyc-checks")
    public List<KycCheck> kycChecks(@PathVariable Long id) { return kycChecks.findByApplicationIdOrderByIdAsc(id); }

    @PostMapping("/{id}/documents/{docId}/review")
    @PreAuthorize(OPS)
    public AppDocument reviewDocument(@PathVariable Long id, @PathVariable Long docId, @RequestBody DocumentService.ReviewRequest r) {
        return docs.review(id, docId, r, CurrentUser.get());
    }

    @GetMapping("/{id}/documents")
    public List<AppDocument> documents(@PathVariable Long id) { return docs.list(id); }

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
    public AppDocument upload(@PathVariable Long id, @RequestParam String type, @RequestParam MultipartFile file) {
        return docs.upload(id, type, file, CurrentUser.get());
    }

    // ---- processing (operations) ----

    @PostMapping("/{id}/process")
    @PreAuthorize(OPS)
    public Map<String, Object> process(@PathVariable Long id) {
        var steps = process.process(id, CurrentUser.get());
        return Map.of("steps", steps, "view", view(id));
    }

    @PostMapping("/{id}/kyc/run")
    @PreAuthorize(OPS)
    public LoanApplication runKyc(@PathVariable Long id) { return data.runKyc(id, CurrentUser.get()); }

    @PostMapping("/{id}/kyc/resolve")
    @PreAuthorize(OPS)
    public LoanApplication resolveKyc(@PathVariable Long id, @RequestBody ResolveRequest r) {
        return data.resolveKyc(id, r.verified(), r.note(), CurrentUser.get());
    }

    @PostMapping("/{id}/data/fetch")
    @PreAuthorize(OPS)
    public LoanApplication fetch(@PathVariable Long id) { return data.fetchData(id, CurrentUser.get()); }

    @PostMapping("/{id}/documents/verify")
    @PreAuthorize(OPS)
    public LoanApplication verifyDocs(@PathVariable Long id) { return docs.verify(id, CurrentUser.get()); }

    @PostMapping("/{id}/field-visit")
    @PreAuthorize("hasAnyRole('OPERATIONS','CREDIT_OFFICER','ADMIN')")
    @Transactional
    public LoanApplication fieldVisit(@PathVariable Long id, @RequestBody NoteRequest r) {
        if (r.note() == null || r.note().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Write field visit notes of at least 10 characters");
        LoanApplication a = apps.get(id);
        apps.transition(a, Domain.FIELD, "DONE", "field.visit_completed", CurrentUser.get(), r.note());
        return a;
    }

    @PostMapping("/{id}/decision/run")
    @PreAuthorize(OPS)
    public DecisionRecord runDecision(@PathVariable Long id) { return decisions.run(id, CurrentUser.get()); }

    // ---- risk and credit ----

    @PostMapping("/{id}/fraud/disposition")
    @PreAuthorize("hasRole('FRAUD_ANALYST')")
    public LoanApplication fraudDisposition(@PathVariable Long id, @RequestBody FraudRequest r) {
        return fraud.dispose(id, r.clear(), r.note(), CurrentUser.get());
    }

    @PostMapping("/{id}/sanction")
    @PreAuthorize(CREDIT)
    public SanctionRecord sanction(@PathVariable Long id, @RequestBody SanctionRequest r) {
        return sanctions.approve(id, r.amount(), r.note(), CurrentUser.get());
    }

    @PostMapping("/{id}/sanction/decline")
    @PreAuthorize(CREDIT)
    public SanctionRecord decline(@PathVariable Long id, @RequestBody NoteRequest r) { return sanctions.decline(id, r.note(), CurrentUser.get()); }

    @PostMapping("/{id}/sanction/escalate")
    @PreAuthorize(CREDIT)
    public SanctionRecord escalate(@PathVariable Long id, @RequestBody NoteRequest r) { return sanctions.escalate(id, r.note(), CurrentUser.get()); }

    @PostMapping("/{id}/kfs/accept")
    @PreAuthorize(FRONT)
    public LoanApplication acceptKfs(@PathVariable Long id, @RequestBody OtpRequest r) { return sanctions.acceptKfs(id, r.otp(), CurrentUser.get()); }

    // ---- disbursement ----

    @PostMapping("/{id}/disburse")
    @PreAuthorize(OPS)
    public Disbursement disburse(@PathVariable Long id) { return disbursements.disburse(id, CurrentUser.get()); }

    @PostMapping("/{id}/disburse/retry")
    @PreAuthorize(OPS)
    public LoanApplication retryDisbursement(@PathVariable Long id) { return disbursements.retry(id, CurrentUser.get()); }

    private Map<String, Object> summary(LoanApplication a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appNo", a.getAppNo());
        m.put("applicantName", a.getApplicantName());
        m.put("pan", a.getPan());
        m.put("productCode", a.getProductCode());
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
