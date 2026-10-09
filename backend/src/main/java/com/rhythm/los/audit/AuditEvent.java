package com.rhythm.los.audit;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String actor;
    @Column(name = "actor_role")
    private String actorRole;
    private String action;
    @Column(name = "entity_type")
    private String entityType;
    @Column(name = "entity_id")
    private String entityId;
    @Column(name = "application_id")
    private Long applicationId;
    private String details;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    protected AuditEvent() {}

    public AuditEvent(String actor, String actorRole, String action, String entityType, String entityId, Long applicationId, String details) {
        this.actor = actor;
        this.actorRole = actorRole;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.applicationId = applicationId;
        this.details = details == null ? null : (details.length() > 1000 ? details.substring(0, 1000) : details);
    }

    public Long getId() { return id; }
    public String getActor() { return actor; }
    public String getActorRole() { return actorRole; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public Long getApplicationId() { return applicationId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
