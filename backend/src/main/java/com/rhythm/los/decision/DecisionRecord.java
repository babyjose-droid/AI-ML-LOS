package com.rhythm.los.decision;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Immutable record of one engine run: inputs (hashed), versions, rules, reasons and the credit memo. */
@Entity
@Table(name = "decision_record")
public class DecisionRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id")
    private Long applicationId;
    private String decision;
    private BigDecimal pd;
    private int score;
    @Column(name = "risk_band")
    private String riskBand;
    @Column(name = "fraud_score")
    private int fraudScore;
    @Column(name = "fraud_status")
    private String fraudStatus;
    @Column(name = "kyc_status")
    private String kycStatus;
    @Column(name = "sustainable_income")
    private BigDecimal sustainableIncome;
    @Column(name = "max_emi")
    private BigDecimal maxEmi;
    @Column(name = "requested_emi")
    private BigDecimal requestedEmi;
    @Column(name = "recommended_amount")
    private BigDecimal recommendedAmount;
    @Column(name = "recommended_emi")
    private BigDecimal recommendedEmi;
    private BigDecimal rate;
    @Column(name = "foir_post")
    private BigDecimal foirPost;
    @Column(name = "expected_loss")
    private BigDecimal expectedLoss;
    @Column(name = "delegation_level")
    private String delegationLevel;
    @Column(name = "rules_json")
    private String rulesJson;
    @Column(name = "contributions_json")
    private String contributionsJson;
    @Column(name = "steps_json")
    private String stepsJson;
    @Column(name = "reason_codes")
    private String reasonCodes;
    @Column(name = "conditions_json")
    private String conditionsJson;
    private String narrative;
    @Column(name = "credit_memo")
    private String creditMemo;
    @Column(name = "model_version")
    private String modelVersion;
    @Column(name = "policy_version")
    private String policyVersion;
    @Column(name = "input_hash")
    private String inputHash;
    @JsonIgnore
    @Column(name = "input_json")
    private String inputJson;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public BigDecimal getPd() { return pd; }
    public void setPd(BigDecimal v) { this.pd = v; }
    public int getScore() { return score; }
    public void setScore(int v) { this.score = v; }
    public String getRiskBand() { return riskBand; }
    public void setRiskBand(String v) { this.riskBand = v; }
    public int getFraudScore() { return fraudScore; }
    public void setFraudScore(int v) { this.fraudScore = v; }
    public String getFraudStatus() { return fraudStatus; }
    public void setFraudStatus(String v) { this.fraudStatus = v; }
    public String getKycStatus() { return kycStatus; }
    public void setKycStatus(String v) { this.kycStatus = v; }
    public BigDecimal getSustainableIncome() { return sustainableIncome; }
    public void setSustainableIncome(BigDecimal v) { this.sustainableIncome = v; }
    public BigDecimal getMaxEmi() { return maxEmi; }
    public void setMaxEmi(BigDecimal v) { this.maxEmi = v; }
    public BigDecimal getRequestedEmi() { return requestedEmi; }
    public void setRequestedEmi(BigDecimal v) { this.requestedEmi = v; }
    public BigDecimal getRecommendedAmount() { return recommendedAmount; }
    public void setRecommendedAmount(BigDecimal v) { this.recommendedAmount = v; }
    public BigDecimal getRecommendedEmi() { return recommendedEmi; }
    public void setRecommendedEmi(BigDecimal v) { this.recommendedEmi = v; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal v) { this.rate = v; }
    public BigDecimal getFoirPost() { return foirPost; }
    public void setFoirPost(BigDecimal v) { this.foirPost = v; }
    public BigDecimal getExpectedLoss() { return expectedLoss; }
    public void setExpectedLoss(BigDecimal v) { this.expectedLoss = v; }
    public String getDelegationLevel() { return delegationLevel; }
    public void setDelegationLevel(String v) { this.delegationLevel = v; }
    public String getRulesJson() { return rulesJson; }
    public void setRulesJson(String v) { this.rulesJson = v; }
    public String getContributionsJson() { return contributionsJson; }
    public void setContributionsJson(String v) { this.contributionsJson = v; }
    public String getStepsJson() { return stepsJson; }
    public void setStepsJson(String v) { this.stepsJson = v; }
    public String getReasonCodes() { return reasonCodes; }
    public void setReasonCodes(String v) { this.reasonCodes = v; }
    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String v) { this.conditionsJson = v; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String v) { this.narrative = v; }
    public String getCreditMemo() { return creditMemo; }
    public void setCreditMemo(String v) { this.creditMemo = v; }
    public String getModelVersion() { return modelVersion; }
    public void setModelVersion(String v) { this.modelVersion = v; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String v) { this.policyVersion = v; }
    public String getInputHash() { return inputHash; }
    public void setInputHash(String v) { this.inputHash = v; }
    public String getInputJson() { return inputJson; }
    public void setInputJson(String v) { this.inputJson = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
}
