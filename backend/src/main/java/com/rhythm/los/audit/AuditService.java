package com.rhythm.los.audit;

import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Append-only audit trail. Every state change and user action writes one row. */
@Service
public class AuditService {
    private final AuditRepository repo;

    public AuditService(AuditRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String actor, String role, String action, String entityType, String entityId, Long appId, String details) {
        repo.save(new AuditEvent(actor, role, action, entityType, entityId, appId, details));
    }

    public void record(CurrentUser u, String action, String entityType, String entityId, Long appId, String details) {
        record(u.username(), u.role().name(), action, entityType, entityId, appId, details);
    }
}
