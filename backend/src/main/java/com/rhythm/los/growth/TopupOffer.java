package com.rhythm.los.growth;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "topup_offer")
public class TopupOffer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    private BigDecimal amount;
    private BigDecimal rate;
    @Column(name = "tenure_months") private int tenureMonths;
    private String status = "OFFERED";
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "expires_on") private LocalDate expiresOn;
    @Column(name = "offered_by") private String offeredBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long v) { this.loanId = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal v) { this.rate = v; }
    public int getTenureMonths() { return tenureMonths; }
    public void setTenureMonths(int v) { this.tenureMonths = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public LocalDate getExpiresOn() { return expiresOn; }
    public void setExpiresOn(LocalDate v) { this.expiresOn = v; }
    public String getOfferedBy() { return offeredBy; }
    public void setOfferedBy(String v) { this.offeredBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
