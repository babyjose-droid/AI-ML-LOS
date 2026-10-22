package com.rhythm.los.plan;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Loan plans (housing, education, farm), their documents and tranche releases; institutions and builders. */
@RestController
@RequestMapping("/api")
public class PlanController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER','VALUER','LEGAL_REVIEWER')";

    private final PlanService service;

    public PlanController(PlanService service) { this.service = service; }

    public record CertifyRequest(BigDecimal percent, String note) {}
    public record RulingRequest(String check, String note) {}

    @GetMapping("/applications/{id}/plan")
    @PreAuthorize(STAFF)
    public Map<String, Object> view(@PathVariable Long id) { return service.view(id, CurrentUser.get()); }

    @PutMapping("/applications/{id}/plan")
    @PreAuthorize(STAFF)
    public Map<String, Object> save(@PathVariable Long id, @RequestBody PlanService.PlanRequest r) { return service.save(id, r, CurrentUser.get()); }

    @PostMapping(value = "/applications/{id}/plan/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> upload(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam String docType,
                                      @RequestParam(required = false) Integer trancheSeq) throws Exception {
        return service.upload(id, file, docType, trancheSeq, CurrentUser.get());
    }

    @PostMapping("/applications/{id}/plan/exceptions")
    @PreAuthorize(STAFF)
    public Map<String, Object> ruling(@PathVariable Long id, @RequestBody RulingRequest r) { return service.ruling(id, r.check(), r.note(), CurrentUser.get()); }

    @PostMapping("/tranches/{id}/certify")
    @PreAuthorize(STAFF)
    public Map<String, Object> certify(@PathVariable Long id, @RequestBody CertifyRequest r) { return service.certify(id, r.percent(), r.note(), CurrentUser.get()); }

    @PostMapping("/tranches/{id}/release")
    @PreAuthorize(STAFF)
    public Map<String, Object> release(@PathVariable Long id) { return service.release(id, CurrentUser.get()); }

    @GetMapping("/plan-documents/{id}/content")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.content(id, CurrentUser.get()));
    }

    @GetMapping("/plan-parties")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> parties() { return service.parties(); }

    @PostMapping("/plan-parties")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> create(@RequestBody PlanService.PartyRequest r) { return service.saveParty(null, r, CurrentUser.get()); }

    @PutMapping("/plan-parties/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody PlanService.PartyRequest r) { return service.saveParty(id, r, CurrentUser.get()); }
}
