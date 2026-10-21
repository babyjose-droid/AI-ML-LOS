package com.rhythm.los.trade;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Trade finance: the limit on an application and its drawdowns, the trade book, anchors and buyers. */
@RestController
@RequestMapping("/api")
public class TradeController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";

    private final TradeService service;

    public TradeController(TradeService service) { this.service = service; }

    public record NoteRequest(String note, String reference, String check, BigDecimal amount) {}

    @GetMapping("/applications/{id}/trade")
    @PreAuthorize(STAFF)
    public Map<String, Object> view(@PathVariable Long id) { return service.view(id, CurrentUser.get()); }

    @PutMapping("/applications/{id}/trade")
    @PreAuthorize(STAFF)
    public Map<String, Object> structure(@PathVariable Long id, @RequestBody TradeService.StructureRequest r) { return service.saveStructure(id, r, CurrentUser.get()); }

    @PostMapping("/applications/{id}/trade/freeze")
    @PreAuthorize(STAFF)
    public Map<String, Object> freeze(@PathVariable Long id, @RequestBody NoteRequest r) { return service.freeze(id, true, r.note(), CurrentUser.get()); }

    @PostMapping("/applications/{id}/trade/unfreeze")
    @PreAuthorize(STAFF)
    public Map<String, Object> unfreeze(@PathVariable Long id, @RequestBody NoteRequest r) { return service.freeze(id, false, r.note(), CurrentUser.get()); }

    @PostMapping(value = "/applications/{id}/trade/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF)
    public Map<String, Object> submit(@PathVariable Long id, @RequestPart("file") MultipartFile file, @RequestParam(required = false) Long counterpartyId) throws Exception {
        return service.submit(id, file, counterpartyId, CurrentUser.get());
    }

    @PutMapping("/trade-invoices/{id}")
    @PreAuthorize(STAFF)
    public Map<String, Object> update(@PathVariable Long id, @RequestBody TradeService.InvoiceRequest r) { return service.update(id, r, CurrentUser.get()); }

    @PostMapping("/trade-invoices/{id}/confirm")
    @PreAuthorize(STAFF)
    public Map<String, Object> confirm(@PathVariable Long id, @RequestBody NoteRequest r) { return service.confirm(id, r.reference(), CurrentUser.get()); }

    @PostMapping("/trade-invoices/{id}/fund")
    @PreAuthorize(STAFF)
    public Map<String, Object> fund(@PathVariable Long id) { return service.fund(id, CurrentUser.get()); }

    @PostMapping("/trade-invoices/{id}/repay")
    @PreAuthorize(STAFF)
    public Map<String, Object> repay(@PathVariable Long id, @RequestBody NoteRequest r) { return service.repay(id, r.amount(), r.reference(), CurrentUser.get()); }

    @PostMapping("/trade-invoices/{id}/reject")
    @PreAuthorize(STAFF)
    public Map<String, Object> reject(@PathVariable Long id, @RequestBody NoteRequest r) { return service.reject(id, r.note(), CurrentUser.get()); }

    @PostMapping("/trade-invoices/{id}/exceptions")
    @PreAuthorize(STAFF)
    public Map<String, Object> exception(@PathVariable Long id, @RequestBody NoteRequest r) { return service.ruling(id, r.check(), r.note(), CurrentUser.get()); }

    @GetMapping("/trade-invoices/{id}/content")
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> content(@PathVariable Long id) {
        return ResponseEntity.ok().header("Cache-Control", "private, no-store").body(service.content(id, CurrentUser.get()));
    }

    @GetMapping("/trade-finance")
    @PreAuthorize(STAFF)
    public Map<String, Object> overview() { return service.overview(CurrentUser.get()); }

    @GetMapping("/trade-counterparties")
    @PreAuthorize(STAFF)
    public List<Map<String, Object>> counterparties() { return service.counterparties(); }

    @PostMapping("/trade-counterparties")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> create(@RequestBody TradeService.PartyRequest r) { return service.saveParty(null, r, CurrentUser.get()); }

    @PutMapping("/trade-counterparties/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CRO')")
    public Map<String, Object> updateParty(@PathVariable Long id, @RequestBody TradeService.PartyRequest r) { return service.saveParty(id, r, CurrentUser.get()); }
}
