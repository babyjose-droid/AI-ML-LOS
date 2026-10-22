package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_policy")
public class CollateralPolicy {
    @Id private String type;
    private String label;
    @Column(name = "ec_years") private int ecYears;
    @Column(name = "title_depth_years") private int titleDepthYears;
    private BigDecimal haircut;
    @Column(name = "max_ltv") private BigDecimal maxLtv;
    @Column(name = "ltv_slabs") private String ltvSlabs;
    @Column(name = "valuation_max_age_months") private int valuationMaxAgeMonths;
    @Column(name = "second_valuation_above") private BigDecimal secondValuationAbove;
    @Column(name = "variance_limit") private BigDecimal varianceLimit;
    @Column(name = "min_images") private int minImages;
    @Column(name = "gps_radius_m") private int gpsRadiusM;
    @Column(name = "coverage_min") private BigDecimal coverageMin;
    @Column(name = "revalue_months") private int revalueMonths;
    @Column(name = "required_docs") private String requiredDocs;
    @Column(name = "optional_docs") private String optionalDocs;
    @Column(name = "required_photos") private String requiredPhotos;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt;

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public int getEcYears() { return ecYears; }
    public void setEcYears(int v) { this.ecYears = v; }
    public int getTitleDepthYears() { return titleDepthYears; }
    public void setTitleDepthYears(int v) { this.titleDepthYears = v; }
    public BigDecimal getHaircut() { return haircut; }
    public void setHaircut(BigDecimal v) { this.haircut = v; }
    public String getLtvSlabs() { return ltvSlabs; }
    public void setLtvSlabs(String v) { this.ltvSlabs = v; }
    public BigDecimal getMaxLtv() { return maxLtv; }
    public void setMaxLtv(BigDecimal v) { this.maxLtv = v; }
    public int getValuationMaxAgeMonths() { return valuationMaxAgeMonths; }
    public void setValuationMaxAgeMonths(int v) { this.valuationMaxAgeMonths = v; }
    public BigDecimal getSecondValuationAbove() { return secondValuationAbove; }
    public void setSecondValuationAbove(BigDecimal v) { this.secondValuationAbove = v; }
    public BigDecimal getVarianceLimit() { return varianceLimit; }
    public void setVarianceLimit(BigDecimal v) { this.varianceLimit = v; }
    public int getMinImages() { return minImages; }
    public void setMinImages(int v) { this.minImages = v; }
    public int getGpsRadiusM() { return gpsRadiusM; }
    public void setGpsRadiusM(int v) { this.gpsRadiusM = v; }
    public BigDecimal getCoverageMin() { return coverageMin; }
    public void setCoverageMin(BigDecimal v) { this.coverageMin = v; }
    public int getRevalueMonths() { return revalueMonths; }
    public void setRevalueMonths(int v) { this.revalueMonths = v; }
    public String getRequiredDocs() { return requiredDocs; }
    public void setRequiredDocs(String v) { this.requiredDocs = v; }
    public String getOptionalDocs() { return optionalDocs; }
    public void setOptionalDocs(String v) { this.optionalDocs = v; }
    public String getRequiredPhotos() { return requiredPhotos; }
    public void setRequiredPhotos(String v) { this.requiredPhotos = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
