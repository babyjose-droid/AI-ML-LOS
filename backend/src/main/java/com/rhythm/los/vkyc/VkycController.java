package com.rhythm.los.vkyc;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
public class VkycController {
    /** Who may run calls at all; the product narrows it further (VkycPolicy.officerRoles). */
    static final String OPS = "hasAnyRole('OPERATIONS','ADMIN','CREDIT_OFFICER','CREDIT_MANAGER')";
    static final String VIEW = "hasAnyRole('OPERATIONS','ADMIN','COMPLIANCE','CREDIT_OFFICER','CREDIT_MANAGER','CRO')";

    private final VkycService vkyc;
    private final com.rhythm.los.application.ApplicationService apps;

    public VkycController(VkycService vkyc, com.rhythm.los.application.ApplicationService apps) {
        this.vkyc = vkyc;
        this.apps = apps;
    }

    // ---- official ----

    @PostMapping("/api/applications/{id}/vkyc")
    @PreAuthorize(OPS)
    public VkycService.Created create(@PathVariable Long id, @RequestBody(required = false) CreateRequest r) {
        return vkyc.create(id, r == null ? null : r.partyId(), CurrentUser.get());
    }

    /** partyId: a co-applicant or guarantor; empty for the applicant. */
    public record CreateRequest(Long partyId) {}

    /** The product's video KYC settings for this application. */
    @GetMapping("/api/applications/{id}/vkyc/policy")
    @PreAuthorize(VIEW)
    public VkycPolicy policy(@PathVariable Long id) {
        return vkyc.policy(apps.get(id));
    }

    @GetMapping("/api/applications/{id}/vkyc")
    @PreAuthorize(VIEW)
    public List<VkycSession> list(@PathVariable Long id) { return vkyc.list(id); }

    @GetMapping("/api/vkyc/{sid}")
    @PreAuthorize(VIEW)
    public Map<String, Object> get(@PathVariable Long sid) {
        VkycSession s = vkyc.get(sid);
        boolean mine = CurrentUser.get().username().equals(s.getOfficer());
        return Map.of("session", s, "frames", vkyc.frameList(s), "analysis", s.getAnalysisJson() == null ? "" : s.getAnalysisJson(),
                "challengeCode", mine && s.getChallengeCode() != null ? s.getChallengeCode() : "", "questions", vkyc.questions(s));
    }

    @PostMapping("/api/vkyc/{sid}/start")
    @PreAuthorize(OPS)
    public Map<String, Object> start(@PathVariable Long sid) {
        VkycSession s = vkyc.start(sid, CurrentUser.get());
        return Map.of("session", s, "challengeCode", s.getChallengeCode(), "iceServers", vkyc.iceServers());
    }

    @PostMapping(value = "/api/vkyc/{sid}/frames", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(OPS)
    public Map<String, Object> frames(@PathVariable Long sid, @RequestParam("frames") List<MultipartFile> frames) {
        return vkyc.addFrames(sid, frames, CurrentUser.get());
    }

    @PostMapping(value = "/api/vkyc/{sid}/recording", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(OPS)
    public VkycSession recording(@PathVariable Long sid, @RequestParam("file") MultipartFile file) {
        return vkyc.addRecording(sid, file, CurrentUser.get());
    }

    @PostMapping("/api/vkyc/{sid}/finish")
    @PreAuthorize(OPS)
    public VkycSession finish(@PathVariable Long sid) { return vkyc.finish(sid, CurrentUser.get()); }

    @PostMapping("/api/vkyc/{sid}/decision")
    @PreAuthorize(OPS)
    public VkycSession decide(@PathVariable Long sid, @RequestBody VkycService.DecisionRequest r) { return vkyc.decide(sid, r, CurrentUser.get()); }

    @PostMapping("/api/vkyc/{sid}/signal")
    @PreAuthorize(OPS)
    public Map<String, Object> officerSignal(@PathVariable Long sid, @RequestBody VkycService.SignalRequest r) {
        VkycSession s = vkyc.get(sid);
        if (!CurrentUser.get().username().equals(s.getOfficer())) throw ApiException.forbidden("NOT_YOUR_CALL", "Start the call first");
        return Map.of("id", vkyc.signal(s, "officer", r).getId());
    }

    @GetMapping("/api/vkyc/{sid}/signal")
    @PreAuthorize(OPS)
    public List<Map<String, Object>> officerSignals(@PathVariable Long sid, @RequestParam(defaultValue = "0") long after) {
        return view(vkyc.signalsFor(vkyc.get(sid), "officer", after));
    }

    @GetMapping("/api/vkyc/{sid}/frames/{index}")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> frame(@PathVariable Long sid, @PathVariable int index) {
        VkycSession s = vkyc.get(sid);
        byte[] b = vkyc.frame(s, index);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(vkyc.frameType(s, index))).body(b);
    }

    @GetMapping("/api/vkyc/{sid}/recording")
    @PreAuthorize(VIEW)
    public ResponseEntity<byte[]> getRecording(@PathVariable Long sid) {
        VkycSession s = vkyc.get(sid);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"vkyc-" + sid + ".webm\"")
                .contentType(MediaType.parseMediaType("video/webm")).body(vkyc.recording(s));
    }

    // ---- customer (no login: the link token is the credential) ----

    @GetMapping("/api/vkyc/queue")
    @PreAuthorize(OPS)
    public Map<String, Object> queue() { return vkyc.queue(CurrentUser.get()); }

    @GetMapping("/api/vkyc/{sid}/slots")
    @PreAuthorize(OPS)
    public List<VkycSlots.Slot> staffSlots(@PathVariable Long sid) { return vkyc.slots(vkyc.get(sid)); }

    @PostMapping("/api/vkyc/{sid}/book")
    @PreAuthorize(OPS)
    public VkycSession staffBook(@PathVariable Long sid, @RequestBody VkycService.BookRequest r) { return vkyc.bookByStaff(sid, r, CurrentUser.get()); }

    @GetMapping("/api/vkyc/c/{token}/slots")
    public List<VkycSlots.Slot> slots(@PathVariable String token) { return vkyc.slotsByToken(token); }

    @PostMapping("/api/vkyc/c/{token}/book")
    public Map<String, Object> book(@PathVariable String token, @RequestBody VkycService.BookRequest r) {
        vkyc.book(vkyc.byToken(token), r, "customer");
        return vkyc.customerView(token);
    }

    @GetMapping("/api/vkyc/c/{token}")
    public Map<String, Object> customer(@PathVariable String token) { return vkyc.customerView(token); }

    @PostMapping("/api/vkyc/c/{token}/join")
    public Map<String, Object> join(@PathVariable String token, @RequestBody VkycService.JoinRequest r, HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        return vkyc.join(token, r, ip, req.getHeader("User-Agent"));
    }

    @PostMapping("/api/vkyc/c/{token}/signal")
    public Map<String, Object> customerSignal(@PathVariable String token, @RequestBody VkycService.SignalRequest r) {
        return Map.of("id", vkyc.signal(vkyc.byToken(token), "customer", r).getId());
    }

    @GetMapping("/api/vkyc/c/{token}/signal")
    public Map<String, Object> customerSignals(@PathVariable String token, @RequestParam(defaultValue = "0") long after) {
        VkycSession s = vkyc.byToken(token);
        return Map.of("status", s.getStatus(), "signals", view(vkyc.signalsFor(s, "customer", after)));
    }

    private static List<Map<String, Object>> view(List<VkycSignal> l) {
        return l.stream().map(x -> Map.<String, Object>of("id", x.getId(), "kind", x.getKind(), "payload", x.getPayload())).toList();
    }
}
