package com.rhythm.los.party;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.DataScope;
import com.rhythm.los.security.CurrentUser;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Co-applicants and guarantors: staff endpoints, and the invite link the person opens on their own phone (/api/party/c/...). */
@RestController
public class PartyController {
    static final String ADD = "hasAnyRole('SALES','OPERATIONS','ADMIN','CREDIT_OFFICER')";
    static final String ASSIST = "hasAnyRole('SALES','OPERATIONS','ADMIN','CREDIT_OFFICER','CREDIT_MANAGER')";
    static final String CREDIT = "hasAnyRole('CREDIT_OFFICER','CREDIT_MANAGER','CRO','ADMIN')";
    static final String CHECKS = "hasAnyRole('OPERATIONS','ADMIN','CREDIT_OFFICER')";

    private final PartyService service;
    private final ApplicationService apps;
    private final DataScope scope;
    private final com.rhythm.los.product.ProductService products;

    public PartyController(PartyService service, ApplicationService apps, DataScope scope, com.rhythm.los.product.ProductService products) {
        this.products = products;
        this.service = service;
        this.apps = apps;
        this.scope = scope;
    }

    /** Parties are reached by their own id: the caller must be able to see the application they belong to. */
    private ApplicationParty visible(Long partyId) {
        ApplicationParty p = service.get(partyId);
        if (!scope.canSee(CurrentUser.get(), apps.get(p.getApplicationId()))) throw ApiException.notFound("Co-applicant or guarantor");
        return p;
    }

    @GetMapping("/api/applications/{id}/parties")
    public Map<String, Object> view(@PathVariable Long id) { return service.view(id); }

    /** The product's rules, for the new-application form before the application exists. */
    @GetMapping("/api/products/{code}/party-rules")
    public PartyRules productRules(@PathVariable String code) {
        return PartyRules.parse(products.live(code).getPartyRules());
    }

    @PostMapping("/api/applications/{id}/parties")
    @PreAuthorize(ADD)
    public ApplicationParty add(@PathVariable Long id, @RequestBody PartyService.PartyRequest r) { return service.add(id, r, CurrentUser.get()); }

    @PutMapping("/api/parties/{pid}")
    @PreAuthorize(ADD)
    public ApplicationParty update(@PathVariable Long pid, @RequestBody PartyService.PartyRequest r) {
        visible(pid);
        return service.update(pid, r, CurrentUser.get());
    }

    @PostMapping("/api/parties/{pid}/income")
    @PreAuthorize("hasAnyRole('OPERATIONS','ADMIN','CREDIT_OFFICER','CREDIT_MANAGER','CRO')")
    public ApplicationParty income(@PathVariable Long pid, @RequestBody PartyService.IncomeRequest r) {
        visible(pid);
        return service.updateIncome(pid, r, CurrentUser.get());
    }

    public record Note(String note) {}

    @PostMapping("/api/parties/{pid}/remove")
    @PreAuthorize(ADD)
    public ApplicationParty remove(@PathVariable Long pid, @RequestBody Note r) {
        visible(pid);
        return service.remove(pid, r.note(), CurrentUser.get());
    }

    @PostMapping("/api/parties/{pid}/invite")
    @PreAuthorize(ASSIST)
    public Map<String, Object> invite(@PathVariable Long pid) {
        visible(pid);
        return service.invite(pid, CurrentUser.get());
    }

    @PostMapping("/api/parties/{pid}/otp")
    @PreAuthorize(ASSIST)
    public Map<String, Object> otp(@PathVariable Long pid) {
        visible(pid);
        return service.sendOtp(pid, CurrentUser.get().username());
    }

    @PostMapping("/api/parties/{pid}/consent")
    @PreAuthorize(ASSIST)
    public ApplicationParty consent(@PathVariable Long pid, @RequestBody PartyService.ConsentRequest r) {
        visible(pid);
        return service.consentInBranch(pid, r, CurrentUser.get());
    }

    @PostMapping(value = "/api/parties/{pid}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(ASSIST)
    public PartyDocument upload(@PathVariable Long pid, @RequestPart("file") MultipartFile file, @RequestParam String docType) {
        visible(pid);
        return service.upload(pid, docType, file, CurrentUser.get().username());
    }

    @GetMapping("/api/party-documents/{docId}/content")
    public ResponseEntity<byte[]> content(@PathVariable Long docId) {
        PartyDocument d = service.document(docId);
        visible(d.getPartyId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.getContentType())).header("Cache-Control", "private, no-store").body(service.documentContent(docId));
    }

    @PostMapping("/api/applications/{id}/parties/checks")
    @PreAuthorize(CHECKS)
    public Map<String, Object> checks(@PathVariable Long id) { return Map.of("log", service.runChecks(id, CurrentUser.get())); }

    public record Resolve(boolean verified, String note) {}

    @PostMapping("/api/parties/{pid}/kyc/resolve")
    @PreAuthorize("hasAnyRole('OPERATIONS','ADMIN')")
    public ApplicationParty resolve(@PathVariable Long pid, @RequestBody Resolve r) {
        visible(pid);
        return service.resolveKyc(pid, r.verified(), r.note(), CurrentUser.get());
    }

    public record Otp(String otp) {}

    @PostMapping("/api/parties/{pid}/sign")
    @PreAuthorize(ASSIST)
    public ApplicationParty sign(@PathVariable Long pid, @RequestBody Otp r) {
        visible(pid);
        return service.signInBranch(pid, r.otp(), CurrentUser.get());
    }

    @PostMapping("/api/applications/{id}/party-requests")
    @PreAuthorize(CREDIT)
    public PartyRequirement request(@PathVariable Long id, @RequestBody PartyService.RequirementRequest r) { return service.request(id, r, CurrentUser.get()); }

    @PostMapping("/api/party-requests/{rid}/waive")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO')")
    public PartyRequirement waive(@PathVariable Long rid, @RequestBody Note r) { return service.waive(rid, r.note(), CurrentUser.get()); }

    // ---------------------------------------------------------------- invite link (no staff sign-in)

    @GetMapping("/api/party/c/{token}")
    public Map<String, Object> inviteView(@PathVariable String token, @RequestHeader(value = "X-Party-Session", required = false) String session) {
        if (session != null && !session.isBlank()) {
            try {
                return service.inviteView(service.bySession(token, session), true);
            } catch (ApiException e) {
                // an expired session falls back to the public view
            }
        }
        return service.inviteView(service.byInvite(token), false);
    }

    @PostMapping("/api/party/c/{token}/otp")
    public Map<String, Object> inviteOtp(@PathVariable String token) {
        ApplicationParty p = service.byInvite(token);
        return service.sendOtp(p.getId(), "party:" + p.getPartyNo());
    }

    @PostMapping("/api/party/c/{token}/verify")
    public Map<String, Object> inviteVerify(@PathVariable String token, @RequestBody Otp r) { return service.verifyInvite(token, r.otp()); }

    @PostMapping("/api/party/c/{token}/consent")
    public Map<String, Object> inviteConsent(@PathVariable String token, @RequestHeader("X-Party-Session") String session, @RequestBody PartyService.ConsentRequest r) {
        service.consentByLink(token, session, r);
        return service.inviteView(service.bySession(token, session), true);
    }

    @PostMapping(value = "/api/party/c/{token}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> inviteUpload(@PathVariable String token, @RequestHeader("X-Party-Session") String session,
                                            @RequestPart("file") MultipartFile file, @RequestParam String docType) {
        ApplicationParty p = service.bySession(token, session);
        PartyDocument d = service.upload(p.getId(), docType, file, "party:" + p.getPartyNo());
        return Map.of("docType", d.getDocType(), "status", d.getStatus(), "message", d.getRemarks() == null ? "" : d.getRemarks());
    }

    @PostMapping("/api/party/c/{token}/sign")
    public Map<String, Object> inviteSign(@PathVariable String token, @RequestHeader("X-Party-Session") String session) {
        service.signByLink(token, session);
        return service.inviteView(service.bySession(token, session), true);
    }
}
