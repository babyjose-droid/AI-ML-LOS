package com.rhythm.los.template;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/templates")
@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE','CRO','CREDIT_MANAGER','OPERATIONS')")
public class TemplateController {
    private final TemplateService service;

    public TemplateController(TemplateService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> list() { return service.list(); }

    @GetMapping("/history")
    public List<MessageTemplate> history(@RequestParam String channel, @RequestParam String code, @RequestParam(defaultValue = "en") String language) {
        return service.history(channel.toUpperCase(), code, language);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public MessageTemplate save(@RequestBody TemplateService.SaveRequest r) { return service.save(r, CurrentUser.get()); }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody TemplateService.SaveRequest r) { return service.preview(r); }

    @PostMapping("/revert")
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE')")
    public ResponseEntity<Void> revert(@RequestBody TemplateService.SaveRequest r) {
        service.revert(r.channel().toUpperCase(), r.code(), r.language() == null ? "en" : r.language(), CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
