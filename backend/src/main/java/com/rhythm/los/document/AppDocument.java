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
}
