package com.rhythm.los.disbursement;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "disbursement")
public class Disbursement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    private String status;
    @Column(name = "gross_amount") private BigDecimal grossAmount;
    private BigDecimal deductions;
    @Column(name = "net_amount") private BigDecimal netAmount;
    @Column(name = "beneficiary_account") private String beneficiaryAccount;
    private String ifsc;
    @Column(name = "name_match_score") private BigDecimal nameMatchScore;
    private String utr;
    @Column(name = "failure_reason") private String failureReason;
    @Column(name = "lms_payload") private String lmsPayload;
    private String actor;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal v) { this.grossAmount = v; }
    public BigDecimal getDeductions() { return deductions; }
    public void setDeductions(BigDecimal v) { this.deductions = v; }
    public BigDecimal getNetAmount() { return netAmount; }
    public void setNetAmount(BigDecimal v) { this.netAmount = v; }
    public String getBeneficiaryAccount() { return beneficiaryAccount; }
    public void setBeneficiaryAccount(String v) { this.beneficiaryAccount = v; }
    public String getIfsc() { return ifsc; }
    public void setIfsc(String v) { this.ifsc = v; }
    public BigDecimal getNameMatchScore() { return nameMatchScore; }
    public void setNameMatchScore(BigDecimal v) { this.nameMatchScore = v; }
    public String getUtr() { return utr; }
    public void setUtr(String v) { this.utr = v; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public String getLmsPayload() { return lmsPayload; }
    public void setLmsPayload(String v) { this.lmsPayload = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public Instant getCreatedAt() { return createdAt; }
}
