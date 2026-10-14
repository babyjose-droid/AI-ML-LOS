package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_media")
public class CollateralMedia {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collateral_id") private Long collateralId;
    private String label;
    @Column(name = "file_name") private String fileName;
    @Column(name = "storage_path") private String storagePath;
    private String sha256;
    private String dhash;
    private Double lat;
    private Double lng;
    @Column(name = "distance_m") private Double distanceM;
    @Column(name = "captured_at") private Instant capturedAt;
    private String quality;
    @Column(name = "analysis_json") private String analysisJson;
    @Column(name = "reuse_match") private String reuseMatch;
    @Column(name = "tamper_score") private BigDecimal tamperScore;
    private boolean manipulated;
    @Column(name = "uploaded_by") private String uploadedBy;
    @Column(name = "uploaded_at") private Instant uploadedAt = Instant.now();

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getDhash() { return dhash; }
    public void setDhash(String v) { this.dhash = v; }
    public Double getLat() { return lat; }
    public void setLat(Double v) { this.lat = v; }
    public Double getLng() { return lng; }
    public void setLng(Double v) { this.lng = v; }
    public Double getDistanceM() { return distanceM; }
    public void setDistanceM(Double v) { this.distanceM = v; }
    public Instant getCapturedAt() { return capturedAt; }
    public void setCapturedAt(Instant v) { this.capturedAt = v; }
    public String getQuality() { return quality; }
    public void setQuality(String v) { this.quality = v; }
    public String getAnalysisJson() { return analysisJson; }
    public void setAnalysisJson(String v) { this.analysisJson = v; }
    public String getReuseMatch() { return reuseMatch; }
    public void setReuseMatch(String v) { this.reuseMatch = v; }
    public BigDecimal getTamperScore() { return tamperScore; }
    public void setTamperScore(BigDecimal v) { this.tamperScore = v; }
    public boolean isManipulated() { return manipulated; }
    public void setManipulated(boolean v) { this.manipulated = v; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant v) { this.uploadedAt = v; }
}
