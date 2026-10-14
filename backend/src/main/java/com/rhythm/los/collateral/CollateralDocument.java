package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collateral_document")
public class CollateralDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "collateral_id") private Long collateralId;
    @Column(name = "doc_type") private String docType;
    @Column(name = "doc_number") private String docNumber;
    private String issuer;
    @Column(name = "doc_date") private LocalDate docDate;
    private Integer pages;
    @Column(name = "file_name") private String fileName;
    @Column(name = "storage_path") private String storagePath;
    @Column(name = "file_hash") private String fileHash;
    private int version = 1;
    @Column(name = "extracted_json") private String extractedJson;
    @Column(name = "read_confidence") private BigDecimal readConfidence;
    private String engine;
    @Column(name = "confirmed_by") private String confirmedBy;
    @Column(name = "confirmed_at") private Instant confirmedAt;
    private boolean manipulated;
    @Column(name = "uploaded_by") private String uploadedBy;
    @Column(name = "uploaded_at") private Instant uploadedAt = Instant.now();

    public Long getId() { return id; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public String getDocType() { return docType; }
    public void setDocType(String v) { this.docType = v; }
    public String getDocNumber() { return docNumber; }
    public void setDocNumber(String v) { this.docNumber = v; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String v) { this.issuer = v; }
    public LocalDate getDocDate() { return docDate; }
    public void setDocDate(LocalDate v) { this.docDate = v; }
    public Integer getPages() { return pages; }
    public void setPages(Integer v) { this.pages = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String v) { this.fileHash = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getExtractedJson() { return extractedJson; }
    public void setExtractedJson(String v) { this.extractedJson = v; }
    public BigDecimal getReadConfidence() { return readConfidence; }
    public void setReadConfidence(BigDecimal v) { this.readConfidence = v; }
    public String getEngine() { return engine; }
    public void setEngine(String v) { this.engine = v; }
    public String getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(String v) { this.confirmedBy = v; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant v) { this.confirmedAt = v; }
    public boolean isManipulated() { return manipulated; }
    public void setManipulated(boolean v) { this.manipulated = v; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant v) { this.uploadedAt = v; }
}
