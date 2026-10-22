package com.rhythm.los.pledge;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "pledge_document")
public class PledgeDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "pledge_id") public Long pledgeId;
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
