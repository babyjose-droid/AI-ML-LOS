package com.rhythm.los.asset;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Asset finance: the asset on an application, its documents and checks, dealers and suppliers, asset classes. */
@RestController
@RequestMapping("/api")
public class AssetController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";

    private final AssetService service;

    public AssetController(AssetService service) { this.service = service; }

    public record NoteRequest(String check, String note, String reference) {}

    @GetMapping("/applications/{id}/asset")
    @PreAuthorize(STAFF)
    public Map<String, Object> view(@PathVariable Long id) { return service.view(id, CurrentUser.get()); }

    @PutMapping("/applications/{id}/asset")
    @PreAuthorize(STAFF)
    public Map<String, Object> save(@PathVariable Long id, @RequestBody AssetService.AssetRequest r) { return service.save(id, r, CurrentUser.get()); }

    @PostMapping(value = "/applications/{id}/asset/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> upload(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam String docType) throws Exception {
        return service.upload(id, file, docType, CurrentUser.get());
    }

    @PostMapping("/applications/{id}/asset/verify-down-payment")
    @PreAuthorize(STAFF)
    public Map<String, Object> verify(@PathVariable Long id, @RequestBody(required = false) NoteRequest r) {
        return service.verifyDownPayment(id, r == null ? null : r.reference(), CurrentUser.get());
    }

    @PostMapping("/applications/{id}/asset/exceptions")
    @PreAuthorize(STAFF)
    public Map<String, Object> exception(@PathVariable Long id, @RequestBody NoteRequest r) { return service.ruling(id, r.check(), r.note(), CurrentUser.get()); }

    @PostMapping("/applications/{id}/asset/evaluate")
    @PreAuthorize(STAFF)
    public Map<String, Object> evaluate(@PathVariable Long id) { return service.reevaluate(id, CurrentUser.get()); }

    @GetMapping("/asset-documents/{docId}/content")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> content(@PathVariable Long docId) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.content(docId, CurrentUser.get()));
    }

    @GetMapping("/asset-finance")
    @PreAuthorize(STAFF)
    public Map<String, Object> overview() { return service.overview(CurrentUser.get()); }

    @GetMapping("/dealers")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> dealers() { return service.dealers(); }

    @PostMapping("/dealers")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> createDealer(@RequestBody AssetService.DealerRequest r) { return service.saveDealer(null, r, CurrentUser.get()); }

    @PutMapping("/dealers/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> updateDealer(@PathVariable Long id, @RequestBody AssetService.DealerRequest r) { return service.saveDealer(id, r, CurrentUser.get()); }

    @GetMapping("/asset-policies")
    @PreAuthorize(STAFF)
    public List<AssetPolicy> policies() { return service.policies(); }

    @PutMapping("/asset-policies/{cls}")
    @PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public AssetPolicy savePolicy(@PathVariable String cls, @RequestBody AssetService.PolicyRequest r) { return service.savePolicy(cls, r, CurrentUser.get()); }
}
