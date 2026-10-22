package com.rhythm.los.plan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A plan document (admission or fee letter, fee demand, 7/12, estimate, stage certificate, insurance) and what AI read. */
@Entity
@Table(name = "plan_document")
public class PlanDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "doc_type") public String docType;
    @Column(name = "file_name") public String fileName;
    @Column(name = "storage_path") @com.fasterxml.jackson.annotation.JsonIgnore public String storagePath;
    @Column(name = "file_hash") public String fileHash;
    @Column(name = "fields_json") public String fieldsJson;
    @Column(name = "read_confidence") public BigDecimal readConfidence;
    public String engine;
    @Column(name = "tranche_seq") public Integer trancheSeq;
    @Column(name = "uploaded_by") public String uploadedBy;
    @Column(name = "uploaded_at") public Instant uploadedAt = Instant.now();
}
