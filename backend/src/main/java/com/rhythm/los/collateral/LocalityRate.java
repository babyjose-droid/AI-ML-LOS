package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "locality_rate")
public class LocalityRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "pin_prefix") private String pinPrefix;
    private String locality;
    private String type;
    @Column(name = "rate_per_sqft") private BigDecimal ratePerSqft;
    @Column(name = "as_of") private LocalDate asOf;
    private String source;

    public Long getId() { return id; }
    public String getPinPrefix() { return pinPrefix; }
    public void setPinPrefix(String v) { this.pinPrefix = v; }
    public String getLocality() { return locality; }
    public void setLocality(String v) { this.locality = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public BigDecimal getRatePerSqft() { return ratePerSqft; }
    public void setRatePerSqft(BigDecimal v) { this.ratePerSqft = v; }
    public LocalDate getAsOf() { return asOf; }
    public void setAsOf(LocalDate v) { this.asOf = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
}
