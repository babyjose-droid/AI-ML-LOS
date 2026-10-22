package com.rhythm.los.pledge;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Loans against securities and deposits: the pledge, the lien, prices, margin calls. */
@RestController
@RequestMapping("/api")
public class PledgeController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE')";

    private final PledgeService service;

    public PledgeController(PledgeService service) { this.service = service; }

    public record Body(String ref, String note, String check, String isin, BigDecimal quantity, BigDecimal amount, String csv) {}

    @GetMapping("/applications/{id}/pledge")
    @PreAuthorize(STAFF)
    public Map<String, Object> view(@PathVariable Long id) { return service.view(id, CurrentUser.get()); }

    @PutMapping("/applications/{id}/pledge")
    @PreAuthorize(STAFF)
    public Map<String, Object> save(@PathVariable Long id, @RequestBody PledgeService.PledgeRequest r) { return service.save(id, r, CurrentUser.get()); }

    @PostMapping(value = "/applications/{id}/pledge/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> upload(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam String docType) throws Exception {
        return service.upload(id, file, docType, CurrentUser.get());
    }

    @PostMapping("/applications/{id}/pledge/lien")
    @PreAuthorize(STAFF)
    public Map<String, Object> lien(@PathVariable Long id, @RequestBody Body b) { return service.lien(id, b.ref(), CurrentUser.get()); }

    @PostMapping("/applications/{id}/pledge/exceptions")
    @PreAuthorize(STAFF)
    public Map<String, Object> ruling(@PathVariable Long id, @RequestBody Body b) { return service.ruling(id, b.check(), b.note(), CurrentUser.get()); }

    @PostMapping("/applications/{id}/pledge/top-up")
    @PreAuthorize(STAFF)
    public Map<String, Object> topUp(@PathVariable Long id, @RequestBody Body b) { return service.pledgeMore(id, b.isin(), b.quantity(), b.ref(), CurrentUser.get()); }

    @PostMapping("/applications/{id}/pledge/part-payment")
    @PreAuthorize(STAFF)
    public Map<String, Object> pay(@PathVariable Long id, @RequestBody Body b) { return service.partPayment(id, b.amount(), b.ref(), CurrentUser.get()); }

    @PostMapping("/margin-calls/{id}/invoke")
    @PreAuthorize(STAFF)
    public Map<String, Object> invoke(@PathVariable Long id, @RequestBody Body b) { return service.invoke(id, b.note(), CurrentUser.get()); }

    @GetMapping("/pledge-documents/{id}/content")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.content(id, CurrentUser.get()));
    }

    @GetMapping("/pledges")
    @PreAuthorize(STAFF)
    public Map<String, Object> monitor() { return service.monitor(CurrentUser.get()); }

    @GetMapping("/securities")
    @PreAuthorize(STAFF)
    public List<SecurityMaster> securities() { return service.securities(); }

    @PutMapping("/securities")
    @PreAuthorize(STAFF)
    public Map<String, Object> saveSecurity(@RequestBody PledgeService.SecurityRequest r) { return service.saveSecurity(r, CurrentUser.get()); }

    @PostMapping("/securities/prices")
    @PreAuthorize(STAFF)
    public Map<String, Object> prices(@RequestBody Body b) { return service.loadPrices(b.csv(), CurrentUser.get()); }
}
