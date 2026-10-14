package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_valuation")
public class CollateralValuation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collateral_id") private Long collateralId;
    private String source;
    private String valuer;
    @Column(name = "report_date") private LocalDate reportDate;
    @Column(name = "market_value") private BigDecimal marketValue;
    @Column(name = "realisable_value") private BigDecimal realisableValue;
    @Column(name = "distress_value") private BigDecimal distressValue;
    private String method;
    private BigDecimal confidence;
    @Column(name = "details_json") private String detailsJson;
    private boolean approved = true;
    @Column(name = "recorded_by") private String recordedBy;
    @Column(name = "recorded_at") private Instant recordedAt = Instant.now();

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getValuer() { return valuer; }
    public void setValuer(String v) { this.valuer = v; }
    public LocalDate getReportDate() { return reportDate; }
    public void setReportDate(LocalDate v) { this.reportDate = v; }
    public BigDecimal getMarketValue() { return marketValue; }
    public void setMarketValue(BigDecimal v) { this.marketValue = v; }
    public BigDecimal getRealisableValue() { return realisableValue; }
    public void setRealisableValue(BigDecimal v) { this.realisableValue = v; }
    public BigDecimal getDistressValue() { return distressValue; }
    public void setDistressValue(BigDecimal v) { this.distressValue = v; }
    public String getMethod() { return method; }
    public void setMethod(String v) { this.method = v; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal v) { this.confidence = v; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String v) { this.detailsJson = v; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean v) { this.approved = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant v) { this.recordedAt = v; }
}
