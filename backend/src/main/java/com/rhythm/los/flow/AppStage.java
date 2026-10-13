package com.rhythm.los.flow;

import jakarta.persistence.*;
import java.time.Instant;

/** Where one application is in one workflow stage. */
@Entity
@Table(name = "app_stage")
public class AppStage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "stage_key") private String stageKey;
    private String status;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "due_at") private Instant dueAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "completed_by") private String completedBy;
    private String outcome;
    private String note;
    @Column(name = "data_json") private String dataJson;
    @Column(name = "skip_reason") private String skipReason;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "breached_at") private Instant breachedAt;
    @Column(name = "breach_action") private String breachAction;
    @Column(name = "escalated_to") private String escalatedTo;

    protected AppStage() {}

    public AppStage(Long applicationId, String stageKey) {
        this.applicationId = applicationId;
        this.stageKey = stageKey;
        this.status = "PENDING";
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getStageKey() { return stageKey; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant v) { this.dueAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getDataJson() { return dataJson; }
    public void setDataJson(String v) { this.dataJson = v; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String v) { this.skipReason = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public Instant getBreachedAt() { return breachedAt; }
    public void setBreachedAt(Instant v) { this.breachedAt = v; }
    public String getBreachAction() { return breachAction; }
    public void setBreachAction(String v) { this.breachAction = v; }
    public String getEscalatedTo() { return escalatedTo; }
    public void setEscalatedTo(String v) { this.escalatedTo = v; }
}
