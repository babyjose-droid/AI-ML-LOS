package com.rhythm.los.docgen;

import jakarta.persistence.*;
import java.time.Instant;

/** A document made for an application from a template version. */
@Entity
@Table(name = "loan_doc")
public class LoanDoc {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "template_code") private String templateCode;
    @Column(name = "template_id") private Long templateId;
    @Column(name = "template_version") private int templateVersion;
    private String language;
    private String stage;
    private String name;
    @com.fasterxml.jackson.annotation.JsonIgnore private String html;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "storage_path") private String storagePath;
    private String sha256;
    @Column(name = "data_hash") private String dataHash;
    @com.fasterxml.jackson.annotation.JsonIgnore private String fields = "";
    private int bytes;
    private String signers = "";
    private String status = "CURRENT";
    private String missing;
    @Column(name = "generated_by") private String generatedBy;
    @Column(name = "generated_at") private Instant generatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String v) { this.templateCode = v; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long v) { this.templateId = v; }
    public int getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(int v) { this.templateVersion = v; }
    public String getLanguage() { return language; }
    public void setLanguage(String v) { this.language = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { this.stage = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getHtml() { return html; }
    public void setHtml(String v) { this.html = v; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String v) { this.storagePath = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getDataHash() { return dataHash; }
    public void setDataHash(String v) { this.dataHash = v; }
    public int getBytes() { return bytes; }
    public void setBytes(int v) { this.bytes = v; }
    public String getSigners() { return signers; }
    public void setSigners(String v) { this.signers = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getMissing() { return missing; }
    public void setMissing(String v) { this.missing = v; }
    public String getGeneratedBy() { return generatedBy; }
    public void setGeneratedBy(String v) { this.generatedBy = v; }
    public Instant getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(Instant v) { this.generatedAt = v; }
    public String getFields() { return fields; }
    public void setFields(String v) { this.fields = v; }
}
