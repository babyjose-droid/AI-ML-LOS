package com.rhythm.los.audit;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditRepository repo;

    public AuditController(AuditRepository repo) { this.repo = repo; }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE','CRO','CREDIT_MANAGER')")
    public List<AuditEvent> list(@RequestParam(required = false) Long applicationId,
                                 @RequestParam(defaultValue = "200") int limit) {
        if (applicationId != null) return repo.findByApplicationIdOrderByIdDesc(applicationId);
        return repo.findAllByOrderByIdDesc(PageRequest.of(0, Math.min(limit, 1000)));
    }
}
