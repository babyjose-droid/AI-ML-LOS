package com.rhythm.los.flow;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "workflow_version")
public class WorkflowVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "product_code") private String productCode;
    private int version;
    private String status;
    @Column(name = "stages_json") private String stagesJson;
    private String notes;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "decision_note") private String decisionNote;

    public Long getId() { return id; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String v) { this.productCode = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getStagesJson() { return stagesJson; }
    public void setStagesJson(String v) { this.stagesJson = v; }
    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { this.approvedBy = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { this.approvedAt = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
}
