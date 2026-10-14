package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral")
public class Collateral {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "col_no") private String colNo;
    private String type;
    private String description;
    @Column(name = "owner_name") private String ownerName;
    private String address;
    private String pincode;
    private Double lat;
    private Double lng;
    @Column(name = "survey_no") private String surveyNo;
    @Column(name = "municipal_id") private String municipalId;
    @Column(name = "area_sqft") private BigDecimal areaSqft;
    @Column(name = "construction_stage") private String constructionStage;
    private String occupancy;
    private String condition;
    @Column(name = "registration_no") private String registrationNo;
    @Column(name = "make_model") private String makeModel;
    @Column(name = "manufacture_year") private Integer manufactureYear;
    @Column(name = "new_price") private BigDecimal newPrice;
    private String insurer;
    @Column(name = "insurance_policy_no") private String insurancePolicyNo;
    @Column(name = "insurance_cover") private BigDecimal insuranceCover;
    @Column(name = "insurance_expiry") private LocalDate insuranceExpiry;
    @Column(name = "insurance_assigned") private boolean insuranceAssigned;
    private String status = "PENDING";
    @Column(name = "risk_score") private Integer riskScore;
    @Column(name = "max_exposure") private BigDecimal maxExposure;
    @Column(name = "ltv_cap") private boolean ltvCap;
    @Column(name = "last_evaluated_at") private Instant lastEvaluatedAt;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getColNo() { return colNo; }
    public void setColNo(String v) { this.colNo = v; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String v) { this.ownerName = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getPincode() { return pincode; }
    public void setPincode(String v) { this.pincode = v; }
    public Double getLat() { return lat; }
    public void setLat(Double v) { this.lat = v; }
    public Double getLng() { return lng; }
    public void setLng(Double v) { this.lng = v; }
    public String getSurveyNo() { return surveyNo; }
    public void setSurveyNo(String v) { this.surveyNo = v; }
    public String getMunicipalId() { return municipalId; }
    public void setMunicipalId(String v) { this.municipalId = v; }
    public BigDecimal getAreaSqft() { return areaSqft; }
    public void setAreaSqft(BigDecimal v) { this.areaSqft = v; }
    public String getConstructionStage() { return constructionStage; }
    public void setConstructionStage(String v) { this.constructionStage = v; }
    public String getOccupancy() { return occupancy; }
    public void setOccupancy(String v) { this.occupancy = v; }
    public String getCondition() { return condition; }
    public void setCondition(String v) { this.condition = v; }
    public String getRegistrationNo() { return registrationNo; }
    public void setRegistrationNo(String v) { this.registrationNo = v; }
    public String getMakeModel() { return makeModel; }
    public void setMakeModel(String v) { this.makeModel = v; }
    public Integer getManufactureYear() { return manufactureYear; }
    public void setManufactureYear(Integer v) { this.manufactureYear = v; }
    public BigDecimal getNewPrice() { return newPrice; }
    public void setNewPrice(BigDecimal v) { this.newPrice = v; }
    public String getInsurer() { return insurer; }
    public void setInsurer(String v) { this.insurer = v; }
    public String getInsurancePolicyNo() { return insurancePolicyNo; }
    public void setInsurancePolicyNo(String v) { this.insurancePolicyNo = v; }
    public BigDecimal getInsuranceCover() { return insuranceCover; }
    public void setInsuranceCover(BigDecimal v) { this.insuranceCover = v; }
    public LocalDate getInsuranceExpiry() { return insuranceExpiry; }
    public void setInsuranceExpiry(LocalDate v) { this.insuranceExpiry = v; }
    public boolean isInsuranceAssigned() { return insuranceAssigned; }
    public void setInsuranceAssigned(boolean v) { this.insuranceAssigned = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Integer getRiskScore() { return riskScore; }
    public void setRiskScore(Integer v) { this.riskScore = v; }
    public BigDecimal getMaxExposure() { return maxExposure; }
    public void setMaxExposure(BigDecimal v) { this.maxExposure = v; }
    public boolean isLtvCap() { return ltvCap; }
    public void setLtvCap(boolean v) { this.ltvCap = v; }
    public Instant getLastEvaluatedAt() { return lastEvaluatedAt; }
    public void setLastEvaluatedAt(Instant v) { this.lastEvaluatedAt = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
