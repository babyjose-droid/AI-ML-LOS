package com.rhythm.los.risk;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "household_member")
public class HouseholdMember {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    private String name;
    private String relation;
    private String occupation;
    @Column(name = "monthly_income") private BigDecimal monthlyIncome = BigDecimal.ZERO;
    private boolean included = true;

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getRelation() { return relation; }
    public void setRelation(String v) { this.relation = v; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String v) { this.occupation = v; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal v) { this.monthlyIncome = v; }
    public boolean isIncluded() { return included; }
    public void setIncluded(boolean v) { this.included = v; }
}
