package com.rhythm.los.field;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "field_photo")
public class FieldPhoto {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "visit_id") private Long visitId;
    @Column(name = "application_id") private Long applicationId;
    private String kind;
    @JsonIgnore @Column(name = "file_path") private String filePath;
    @Column(name = "content_type") private String contentType;
    @Column(name = "size_bytes") private long sizeBytes;
    private String sha256;
    private String dhash;
    @Column(name = "taken_at") private Instant takenAt;
    private Double lat;
    private Double lng;
    @Column(name = "checks_json") private String checksJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getVisitId() { return visitId; }
    public void setVisitId(Long v) { this.visitId = v; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { this.filePath = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getDhash() { return dhash; }
    public void setDhash(String v) { this.dhash = v; }
    public Instant getTakenAt() { return takenAt; }
    public void setTakenAt(Instant v) { this.takenAt = v; }
    public Double getLat() { return lat; }
    public void setLat(Double v) { this.lat = v; }
    public Double getLng() { return lng; }
    public void setLng(Double v) { this.lng = v; }
    public String getChecksJson() { return checksJson; }
    public void setChecksJson(String v) { this.checksJson = v; }
    public Instant getCreatedAt() { return createdAt; }
}
