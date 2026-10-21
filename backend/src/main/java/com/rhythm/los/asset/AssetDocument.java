package com.rhythm.los.asset;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A quotation, invoice, receipt, insurance or post-disbursal document for an asset, with what was read from it. */
@Entity
@Table(name = "asset_document")
public class AssetDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "asset_id") public Long assetId;
    @Column(name = "doc_type") public String docType;
    @Column(name = "file_name") public String fileName;
    @Column(name = "storage_path") @com.fasterxml.jackson.annotation.JsonIgnore public String storagePath;
    @Column(name = "file_hash") public String fileHash;
    @Column(name = "fields_json") public String fieldsJson;
    @Column(name = "read_confidence") public BigDecimal readConfidence;
    public String engine;
    @Column(name = "uploaded_by") public String uploadedBy;
    @Column(name = "uploaded_at") public Instant uploadedAt = Instant.now();
}
