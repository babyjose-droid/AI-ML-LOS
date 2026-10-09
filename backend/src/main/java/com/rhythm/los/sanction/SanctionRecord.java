package com.rhythm.los.sanction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "sanction_record")
public class SanctionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "decision_id") private Long decisionId;
    private String action;
    private String level;
    private BigDecimal amount;
    private BigDecimal rate;
    @Column(name = "tenure_months") private Integer tenureMonths;
    @Column(name = "override_flag") private boolean overrideFlag;
    private String note;
    @JsonIgnore @Column(name = "kfs_json") private String kfsJson;
    @Column(name = "kfs_accepted_at") private Instant kfsAcceptedAt;
    private String actor;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public Long getDecisionId() { return decisionId; }
    public void setDecisionId(Long v) { this.decisionId = v; }
    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }
    public String getLevel() { return level; }
    public void setLevel(String v) { this.level = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal v) { this.rate = v; }
    public Integer getTenureMonths() { return tenureMonths; }
    public void setTenureMonths(Integer v) { this.tenureMonths = v; }
    public boolean isOverrideFlag() { return overrideFlag; }
    public void setOverrideFlag(boolean v) { this.overrideFlag = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getKfsJson() { return kfsJson; }
    public void setKfsJson(String v) { this.kfsJson = v; }
    public Instant getKfsAcceptedAt() { return kfsAcceptedAt; }
    public void setKfsAcceptedAt(Instant v) { this.kfsAcceptedAt = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getCreatedAt() { return createdAt; }
}
