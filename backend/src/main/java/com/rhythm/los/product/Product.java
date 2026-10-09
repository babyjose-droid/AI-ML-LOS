package com.rhythm.los.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "product")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String segment;
    private boolean secured;
    private String status = "LIVE";
    private int version = 1;
    @Column(name = "min_amount") private BigDecimal minAmount;
    @Column(name = "max_amount") private BigDecimal maxAmount;
    @Column(name = "min_tenure") private int minTenure;
    @Column(name = "max_tenure") private int maxTenure;
    @Column(name = "min_age") private int minAge;
    @Column(name = "max_age") private int maxAge;
    @Column(name = "processing_fee_pct") private BigDecimal processingFeePct;
    @Column(name = "rate_min") private BigDecimal rateMin;
    @Column(name = "rate_max") private BigDecimal rateMax;
    @Column(name = "field_visit_rule") private String fieldVisitRule;
    @Column(name = "field_visit_threshold") private BigDecimal fieldVisitThreshold;
    @Column(name = "required_docs") private String requiredDocs;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public List<String> requiredDocList() {
        return Arrays.stream(requiredDocs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean fieldVisitNeeded(double amount) {
        return switch (fieldVisitRule) {
            case "ALWAYS" -> true;
            case "ABOVE_AMOUNT" -> fieldVisitThreshold != null && amount > fieldVisitThreshold.doubleValue();
            default -> false;
        };
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }
    public boolean isSecured() { return secured; }
    public void setSecured(boolean secured) { this.secured = secured; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal v) { this.minAmount = v; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal v) { this.maxAmount = v; }
    public int getMinTenure() { return minTenure; }
    public void setMinTenure(int v) { this.minTenure = v; }
    public int getMaxTenure() { return maxTenure; }
    public void setMaxTenure(int v) { this.maxTenure = v; }
    public int getMinAge() { return minAge; }
    public void setMinAge(int v) { this.minAge = v; }
    public int getMaxAge() { return maxAge; }
    public void setMaxAge(int v) { this.maxAge = v; }
    public BigDecimal getProcessingFeePct() { return processingFeePct; }
    public void setProcessingFeePct(BigDecimal v) { this.processingFeePct = v; }
    public BigDecimal getRateMin() { return rateMin; }
    public void setRateMin(BigDecimal v) { this.rateMin = v; }
    public BigDecimal getRateMax() { return rateMax; }
    public void setRateMax(BigDecimal v) { this.rateMax = v; }
    public String getFieldVisitRule() { return fieldVisitRule; }
    public void setFieldVisitRule(String v) { this.fieldVisitRule = v; }
    public BigDecimal getFieldVisitThreshold() { return fieldVisitThreshold; }
    public void setFieldVisitThreshold(BigDecimal v) { this.fieldVisitThreshold = v; }
    public String getRequiredDocs() { return requiredDocs; }
    public void setRequiredDocs(String v) { this.requiredDocs = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
