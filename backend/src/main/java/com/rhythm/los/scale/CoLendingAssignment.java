package com.rhythm.los.scale;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "colending_assignment")
public class CoLendingAssignment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "partner_id") private Long partnerId;
    @Column(name = "application_id") private Long applicationId;
    private BigDecimal amount;
    @Column(name = "partner_amount") private BigDecimal partnerAmount;
    @Column(name = "loan_rate") private BigDecimal loanRate;
    @Column(name = "partner_rate") private BigDecimal partnerRate;
    @Column(name = "nbfc_yield") private BigDecimal nbfcYield;
    private String status = "SUBMITTED";
    private String batch;
    @Column(name = "partner_ref") private String partnerRef;
    private String note;
    @Column(name = "submitted_by") private String submittedBy;
    @Column(name = "submitted_at") private Instant submittedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;

    public Long getId() { return id; }
    public Long getPartnerId() { return partnerId; }
    public void setPartnerId(Long v) { this.partnerId = v; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getPartnerAmount() { return partnerAmount; }
    public void setPartnerAmount(BigDecimal v) { this.partnerAmount = v; }
    public BigDecimal getLoanRate() { return loanRate; }
    public void setLoanRate(BigDecimal v) { this.loanRate = v; }
    public BigDecimal getPartnerRate() { return partnerRate; }
    public void setPartnerRate(BigDecimal v) { this.partnerRate = v; }
    public BigDecimal getNbfcYield() { return nbfcYield; }
    public void setNbfcYield(BigDecimal v) { this.nbfcYield = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getBatch() { return batch; }
    public void setBatch(String v) { this.batch = v; }
    public String getPartnerRef() { return partnerRef; }
    public void setPartnerRef(String v) { this.partnerRef = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(String v) { this.submittedBy = v; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant v) { this.submittedAt = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
}
