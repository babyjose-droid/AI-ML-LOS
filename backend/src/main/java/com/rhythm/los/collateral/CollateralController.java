package com.rhythm.los.collateral;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Collateral AI: SCR-140 to SCR-149 and the APIs of the FSD (paths under /api). */
@RestController
@RequestMapping("/api")
public class CollateralController {
    static final String VIEW = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','COMPLIANCE','FIELD_OFFICER','LEGAL_REVIEWER','VALUER','FRAUD_ANALYST')";
    static final String CAPTURE = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','FIELD_OFFICER','LEGAL_REVIEWER')";
    static final String PHOTOS = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','FIELD_OFFICER','VALUER')";
    static final String REVIEW = "hasAnyRole('ADMIN','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FIELD_OFFICER','LEGAL_REVIEWER','VALUER','COMPLIANCE')";

    private final CollateralService service;

    public CollateralController(CollateralService service) { this.service = service; }

    @PostMapping("/applications/{id}/collaterals")
    @PreAuthorize(CAPTURE)
    public Collateral declare(@PathVariable Long id, @RequestBody CollateralService.Declare r) { return service.declare(id, r, CurrentUser.get()); }

    @GetMapping("/applications/{id}/collaterals")
    @PreAuthorize(VIEW)
    public List<Map<String, Object>> forApplication(@PathVariable Long id) { return service.forApplication(id, CurrentUser.get()); }

    @GetMapping("/applications/{id}/unified-decision")
    @PreAuthorize(VIEW)
    public Map<String, Object> unified(@PathVariable Long id) { return service.unifiedFor(id, CurrentUser.get()); }

    @GetMapping("/collaterals")
    @PreAuthorize(VIEW)
    public Map<String, Object> overview(@RequestParam(required = false) String status, @RequestParam(required = false) String type) {
        return service.overview(CurrentUser.get(), status, type);
    }

    @GetMapping("/collaterals/{id}")
    @PreAuthorize(VIEW)
    public Map<String, Object> detail(@PathVariable Long id) { return service.detail(id, CurrentUser.get()); }

    @GetMapping("/collaterals/{id}/360")
    @PreAuthorize(VIEW)
    public Map<String, Object> c360(@PathVariable Long id) { return service.detail(id, CurrentUser.get()); }

    @PutMapping("/collaterals/{id}")
    @PreAuthorize(CAPTURE)
    public Collateral update(@PathVariable Long id, @RequestBody CollateralService.Declare r) { return service.update(id, r, CurrentUser.get()); }

    @PostMapping(value = "/collaterals/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAPTURE)
    public CollateralDocument document(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam String docType,
                                       @RequestParam(required = false) String fields, @RequestParam(required = false) String transactions) throws Exception {
        return service.uploadDocument(id, file, docType, fields, transactions, CurrentUser.get());
    }

    @PostMapping("/collateral-documents/{docId}/confirm")
    @PreAuthorize(CAPTURE)
    public CollateralDocument confirm(@PathVariable Long docId, @RequestBody CollateralService.Confirm r) { return service.confirm(docId, r, CurrentUser.get()); }

    @GetMapping("/collateral-documents/{docId}/content")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> documentContent(@PathVariable Long docId) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.documentContent(docId, CurrentUser.get()));
    }

    @PostMapping(value = "/collaterals/{id}/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(PHOTOS)
    public CollateralMedia mediaUpload(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam String label,
                                       @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng,
                                       @RequestParam(required = false) String capturedAt) throws Exception {
        return service.uploadMedia(id, file, label, lat, lng, capturedAt, CurrentUser.get());
    }

    @GetMapping("/collateral-media/{mediaId}/content")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> mediaContent(@PathVariable Long mediaId) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).header("Cache-Control", "private, max-age=300").body(service.mediaContent(mediaId, CurrentUser.get()));
    }

    public record Manipulation(String note) {}

    @PostMapping("/collateral-documents/{itemId}/manipulated")
    @PreAuthorize(REVIEW)
    public Map<String, Object> documentManipulated(@PathVariable Long itemId, @RequestBody Manipulation m) {
        return service.markManipulated("document", itemId, m.note(), CurrentUser.get());
    }

    @PostMapping("/collateral-media/{itemId}/manipulated")
    @PreAuthorize(REVIEW)
    public Map<String, Object> mediaManipulated(@PathVariable Long itemId, @RequestBody Manipulation m) {
        return service.markManipulated("media", itemId, m.note(), CurrentUser.get());
    }

    @PostMapping("/collaterals/{id}/valuations")
    @PreAuthorize("hasAnyRole('ADMIN','VALUER')")
    public CollateralValuation valuation(@PathVariable Long id, @RequestBody CollateralService.ValuationRequest r) { return service.addValuation(id, r, CurrentUser.get()); }

    public record Appraisal(List<CollateralService.GoldItem> items) {}

    @PostMapping("/collaterals/{id}/gold-appraisal")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER')")
    public CollateralValuation gold(@PathVariable Long id, @RequestBody Appraisal r) { return service.goldAppraisal(id, r.items(), CurrentUser.get()); }

    @PostMapping("/collaterals/{id}/evaluate")
    @PreAuthorize(VIEW)
    public Map<String, Object> evaluate(@PathVariable Long id) { return service.evaluate(id, CurrentUser.get()); }

    @GetMapping("/collaterals/{id}/audit")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE','CRO','CREDIT_MANAGER','LEGAL_REVIEWER')")
    public Map<String, Object> trail(@PathVariable Long id) { return service.trail(id, CurrentUser.get()); }

    @GetMapping("/collateral-reviews")
    @PreAuthorize(REVIEW)
    public List<Map<String, Object>> queue(@RequestParam(defaultValue = "OPEN") String status) { return service.queue(CurrentUser.get(), status); }

    @PostMapping("/collateral-reviews/{id}/resolve")
    @PreAuthorize(REVIEW)
    public Map<String, Object> resolve(@PathVariable Long id, @RequestBody CollateralService.Resolve r) { return service.resolve(id, r, CurrentUser.get()); }

    @GetMapping("/collateral-alerts")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CREDIT_MANAGER','CRO','COMPLIANCE')")
    public List<Map<String, Object>> alerts() { return service.alerts(CurrentUser.get()); }

    @GetMapping("/collateral-policy")
    @PreAuthorize(VIEW)
    public List<CollateralPolicy> policies() { return service.policies(); }

    @PutMapping("/collateral-policy/{type}")
    @PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public CollateralPolicy updatePolicy(@PathVariable String type, @RequestBody Map<String, Object> r) { return service.updatePolicy(type, r, CurrentUser.get()); }

    @GetMapping("/collateral-rates")
    @PreAuthorize(VIEW)
    public Map<String, Object> rates() { return service.rates(); }

    public record GoldRateRequest(String date, Double rate22k) {}

    @PostMapping("/collateral-rates/gold")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public GoldRate goldRate(@RequestBody GoldRateRequest r) { return service.setGoldRate(r.date(), r.rate22k(), CurrentUser.get()); }
}
