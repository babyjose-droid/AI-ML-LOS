package com.rhythm.los.masters;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** Delegation of powers as data (SCR-122): per sanction level, the amount, worst band and deviations it may approve. */
@Entity
@Table(name = "sanction_limit")
public class SanctionLimit {
    @Id private String level;
    private String role;
    @Column(name = "max_amount") private BigDecimal maxAmount;
    @Column(name = "max_band") private String maxBand;
    @Column(name = "max_deviations") private int maxDeviations;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public String getLevel() { return level; }
    public String getRole() { return role; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal v) { this.maxAmount = v; }
    public String getMaxBand() { return maxBand; }
    public void setMaxBand(String v) { this.maxBand = v; }
    public int getMaxDeviations() { return maxDeviations; }
    public void setMaxDeviations(int v) { this.maxDeviations = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
