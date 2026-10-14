package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_evaluation")
public class CollateralEvaluation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collateral_id") private Long collateralId;
    private String status;
    @Column(name = "risk_score") private int riskScore;
    @Column(name = "risk_json") private String riskJson;
    @Column(name = "rules_json") private String rulesJson;
    @Column(name = "ltv_json") private String ltvJson;
    @Column(name = "ec_json") private String ecJson;
    @Column(name = "title_json") private String titleJson;
    @Column(name = "vision_json") private String visionJson;
    @Column(name = "valuation_json") private String valuationJson;
    private String summary;
    @Column(name = "policy_version") private String policyVersion;
    @Column(name = "model_version") private String modelVersion;
    @Column(name = "evaluated_by") private String evaluatedBy;
    @Column(name = "evaluated_at") private Instant evaluatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int v) { this.riskScore = v; }
    public String getRiskJson() { return riskJson; }
    public void setRiskJson(String v) { this.riskJson = v; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String v) { this.rulesJson = v; }
    public String getLtvJson() { return ltvJson; }
    public void setLtvJson(String v) { this.ltvJson = v; }
    public String getEcJson() { return ecJson; }
    public void setEcJson(String v) { this.ecJson = v; }
    public String getTitleJson() { return titleJson; }
    public void setTitleJson(String v) { this.titleJson = v; }
    public String getVisionJson() { return visionJson; }
    public void setVisionJson(String v) { this.visionJson = v; }
    public String getValuationJson() { return valuationJson; }
    public void setValuationJson(String v) { this.valuationJson = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String v) { this.policyVersion = v; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String v) { this.modelVersion = v; }
    public String getEvaluatedBy() { return evaluatedBy; }
    public void setEvaluatedBy(String v) { this.evaluatedBy = v; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(Instant v) { this.evaluatedAt = v; }
}
