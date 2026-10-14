package com.rhythm.los.risk;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "household")
public class Household {
    @Id @Column(name = "application_id") private Long applicationId;
    private int members;
    @Column(name = "monthly_expenses") private BigDecimal monthlyExpenses = BigDecimal.ZERO;
    @Column(name = "existing_emi") private BigDecimal existingEmi = BigDecimal.ZERO;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public int getMembers() { return members; }
    public void setMembers(int v) { this.members = v; }
    public BigDecimal getMonthlyExpenses() { return monthlyExpenses; }
    public void setMonthlyExpenses(BigDecimal v) { this.monthlyExpenses = v; }
    public BigDecimal getExistingEmi() { return existingEmi; }
    public void setExistingEmi(BigDecimal v) { this.existingEmi = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
