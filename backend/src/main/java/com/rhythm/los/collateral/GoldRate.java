package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "gold_rate")
public class GoldRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "rate_date") private LocalDate rateDate;
    @Column(name = "rate_22k") private BigDecimal rate22k;
    private String source;
    @Column(name = "entered_by") private String enteredBy;

    public Long getId() { return id; }
    public LocalDate getRateDate() { return rateDate; }
    public void setRateDate(LocalDate v) { this.rateDate = v; }
    public BigDecimal getRate22k() { return rate22k; }
    public void setRate22k(BigDecimal v) { this.rate22k = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getEnteredBy() { return enteredBy; }
    public void setEnteredBy(String v) { this.enteredBy = v; }
}
