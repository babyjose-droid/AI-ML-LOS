package com.rhythm.los.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "model_version")
public class ModelVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String version;
    private String status;
    private double intercept;
    @Column(name = "terms_json") private String termsJson;
    @Column(name = "metrics_json") private String metricsJson;
    @Column(name = "data_source") private String dataSource;
    @Column(name = "rows_used") private int rowsUsed;
    @Column(name = "trained_by") private String trainedBy;
    @Column(name = "trained_at") private Instant trainedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note") private String decisionNote;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getVersion() { return version; }
    public void setVersion(String v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public double getIntercept() { return intercept; }
    public void setIntercept(double v) { this.intercept = v; }
    public String getTermsJson() { return termsJson; }
    public void setTermsJson(String v) { this.termsJson = v; }
    public String getMetricsJson() { return metricsJson; }
    public void setMetricsJson(String v) { this.metricsJson = v; }
    public String getDataSource() { return dataSource; }
    public void setDataSource(String v) { this.dataSource = v; }
    public int getRowsUsed() { return rowsUsed; }
    public void setRowsUsed(int v) { this.rowsUsed = v; }
    public String getTrainedBy() { return trainedBy; }
    public void setTrainedBy(String v) { this.trainedBy = v; }
    public Instant getTrainedAt() { return trainedAt; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
}
