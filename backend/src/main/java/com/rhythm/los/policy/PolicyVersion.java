package com.rhythm.los.policy;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "policy_version")
public class PolicyVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String version;
    private String status;
    @Column(name = "params_json") private String paramsJson;
    private String notes;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "based_on") private String basedOn;
    @Column(name = "submitted_at") private Instant submittedAt;
    private String checker;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note") private String decisionNote;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "simulation_json") private String simulationJson;
    @Column(name = "live_from") private Instant liveFrom;
    @Column(name = "retired_at") private Instant retiredAt;
    @Column(name = "effective_from") private Instant effectiveFrom;
    @Column(name = "overrides_json") private String overridesJson;
    @Column(name = "rollback_of") private String rollbackOf;

    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant v) { this.effectiveFrom = v; }
    public String getOverridesJson() { return overridesJson; }
    public void setOverridesJson(String v) { this.overridesJson = v; }
    public String getRollbackOf() { return rollbackOf; }
    public void setRollbackOf(String v) { this.rollbackOf = v; }

    public Long getId() { return id; }
    public String getVersion() { return version; }
    public String getStatus() { return status; }
    public String getParamsJson() { return paramsJson; }
    public String getNotes() { return notes; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getBasedOn() { return basedOn; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getChecker() { return checker; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public String getSimulationJson() { return simulationJson; }
    public boolean isSimulated() { return simulationJson != null; }
    public Instant getLiveFrom() { return liveFrom; }
    public Instant getRetiredAt() { return retiredAt; }

    public void setVersion(String v) { this.version = v; }
    public void setStatus(String v) { this.status = v; }
    public void setParamsJson(String v) { this.paramsJson = v; }
    public void setNotes(String v) { this.notes = v; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public void setBasedOn(String v) { this.basedOn = v; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public void setChecker(String v) { this.checker = v; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
    public void setSimulationJson(String v) { this.simulationJson = v; }
    public void setLiveFrom(Instant v) { this.liveFrom = v; }
    public void setRetiredAt(Instant v) { this.retiredAt = v; }
}
