package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_review")
public class CollateralReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collateral_id") private Long collateralId;
    @Column(name = "rule_id") private String ruleId;
    private String type;
    private String role;
    private String reason;
    @Column(name = "sla_hours") private int slaHours;
    @Column(name = "due_at") private Instant dueAt;
    private String status = "OPEN";
    private String reviewer;
    private String note;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "closed_at") private Instant closedAt;

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public String getRuleId() { return ruleId; }
    public void setRuleId(String v) { this.ruleId = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public int getSlaHours() { return slaHours; }
    public void setSlaHours(int v) { this.slaHours = v; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant v) { this.dueAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getReviewer() { return reviewer; }
    public void setReviewer(String v) { this.reviewer = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
}
