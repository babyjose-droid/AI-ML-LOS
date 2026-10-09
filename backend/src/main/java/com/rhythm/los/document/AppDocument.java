package com.rhythm.los.document;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "app_document")
public class AppDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "doc_type") private String docType;
    @Column(name = "file_name") private String fileName;
    @Column(name = "content_type") private String contentType;
    @Column(name = "size_bytes") private long sizeBytes;
    private String sha256;
    @JsonIgnore @Column(name = "storage_path") private String storagePath;
    private String status;
    private String remarks;
    @Column(name = "tamper_flag") private boolean tamperFlag;
    @Column(name = "read_confidence") private BigDecimal readConfidence;
    @Column(name = "uploaded_by") private String uploadedBy;
    @Column(name = "uploaded_at") private Instant uploadedAt = Instant.now();
    @Column(name = "ai_status") private String aiStatus;
    @Column(name = "detected_type") private String detectedType;
    @Column(name = "type_confidence") private BigDecimal typeConfidence;
    @Column(name = "extracted_json") private String extractedJson;
    @Column(name = "validations_json") private String validationsJson;
    @Column(name = "quality_json") private String qualityJson;
    @Column(name = "tamper_json") private String tamperJson;
    @Column(name = "review_reasons") private String reviewReasons;
    private String engine;
    private boolean masked;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "review_note") private String reviewNote;


    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getDocType() { return docType; }
    public void setDocType(String v) { this.docType = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String v) { this.remarks = v; }
    public boolean isTamperFlag() { return tamperFlag; }
    public void setTamperFlag(boolean v) { this.tamperFlag = v; }
    public BigDecimal getReadConfidence() { return readConfidence; }
    public void setReadConfidence(BigDecimal v) { this.readConfidence = v; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public Instant getUploadedAt() { return uploadedAt; }
    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String v) { this.aiStatus = v; }
    public String getDetectedType() { return detectedType; }
    public void setDetectedType(String v) { this.detectedType = v; }
    public BigDecimal getTypeConfidence() { return typeConfidence; }
    public void setTypeConfidence(BigDecimal v) { this.typeConfidence = v; }
    public String getExtractedJson() { return extractedJson; }
    public void setExtractedJson(String v) { this.extractedJson = v; }
    public String getValidationsJson() { return validationsJson; }
    public void setValidationsJson(String v) { this.validationsJson = v; }
    public String getQualityJson() { return qualityJson; }
    public void setQualityJson(String v) { this.qualityJson = v; }
    public String getTamperJson() { return tamperJson; }
    public void setTamperJson(String v) { this.tamperJson = v; }
    public String getReviewReasons() { return reviewReasons; }
    public void setReviewReasons(String v) { this.reviewReasons = v; }
    public String getEngine() { return engine; }
    public void setEngine(String v) { this.engine = v; }
    public boolean isMasked() { return masked; }
    public void setMasked(boolean v) { this.masked = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { this.reviewedBy = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { this.reviewedAt = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { this.reviewNote = v; }
}
