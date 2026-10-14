package com.rhythm.los.compliance;

import com.rhythm.los.assistant.AssistantService;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Compliance pack, regulatory returns, branch and officer scores, and the policy assistant. */
@RestController
@RequestMapping("/api")
public class ComplianceController {
    static final String COMPLIANCE = "hasAnyRole('ADMIN','COMPLIANCE','CRO')";
    static final String REPORTS = "hasAnyRole('ADMIN','COMPLIANCE','CRO','CREDIT_MANAGER')";
    static final String CIC_FILE = "hasAnyRole('ADMIN','COMPLIANCE')";
    static final String SCORES = "hasAnyRole('ADMIN','CRO','CREDIT_MANAGER','OPERATIONS','COMPLIANCE')";
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";

    private final CompliancePackService pack;
    private final RegulatoryService regulatory;
    private final ScoresService scores;
    private final AssistantService assistant;
    private final AuditService audit;

    public ComplianceController(CompliancePackService pack, RegulatoryService regulatory, ScoresService scores, AssistantService assistant, AuditService audit) {
        this.pack = pack;
        this.regulatory = regulatory;
        this.scores = scores;
        this.assistant = assistant;
        this.audit = audit;
    }

    @GetMapping("/compliance/pack")
    @PreAuthorize(COMPLIANCE)
    public Map<String, Object> pack() { return pack.pack(); }

    @GetMapping(value = "/compliance/pack.csv", produces = "text/csv")
    @PreAuthorize(COMPLIANCE)
    public ResponseEntity<String> packCsv() {
        audit.record(CurrentUser.get(), "compliance.pack_exported", "REPORT", "COMPLIANCE-" + LocalDate.now(), null, null);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=compliance-pack-" + LocalDate.now() + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(pack.csv());
    }

    @GetMapping("/regulatory/asset-classification")
    @PreAuthorize(REPORTS)
    public Map<String, Object> assetClassification() { return regulatory.assetClassification(CurrentUser.get()); }

    @GetMapping("/regulatory/npa-movement")
    @PreAuthorize(REPORTS)
    public Map<String, Object> npaMovement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return regulatory.npaMovement(from, to, CurrentUser.get());
    }

    @GetMapping("/regulatory/exposure")
    @PreAuthorize(REPORTS)
    public Map<String, Object> exposure() { return regulatory.exposure(CurrentUser.get()); }

    @GetMapping("/regulatory/cic")
    @PreAuthorize(REPORTS)
    public Map<String, Object> cicCheck(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return regulatory.cic(asOf, CurrentUser.get(), false);
    }

    @GetMapping(value = "/regulatory/cic.csv", produces = "text/csv")
    @PreAuthorize(CIC_FILE)
    public ResponseEntity<String> cicFile(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        Map<String, Object> m = regulatory.cic(asOf, CurrentUser.get(), true);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=cic-" + m.get("asOf") + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body((String) m.get("file"));
    }

    @GetMapping("/scores/branches")
    @PreAuthorize(SCORES)
    public List<Map<String, Object>> branches(@RequestParam(defaultValue = "90") int days) { return scores.branchScores(CurrentUser.get(), Math.max(7, Math.min(730, days))); }

    @GetMapping("/scores/officers")
    @PreAuthorize(SCORES)
    public List<Map<String, Object>> officers(@RequestParam(defaultValue = "90") int days) { return scores.officerScores(CurrentUser.get(), Math.max(7, Math.min(730, days))); }

    public record Question(String question) {}

    @PostMapping("/assistant/ask")
    @PreAuthorize(STAFF)
    public Map<String, Object> ask(@RequestBody Question q) { return assistant.ask(q.question(), CurrentUser.get()); }
}
