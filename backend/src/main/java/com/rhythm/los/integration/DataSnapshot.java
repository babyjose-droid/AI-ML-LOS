package com.rhythm.los.integration;

import jakarta.persistence.*;
import java.time.Instant;

/** Raw data received from a vendor, kept as evidence for the decision record. */
@Entity
@Table(name = "data_snapshot")
public class DataSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    private String kind;
    private String vendor;
    @Column(name = "payload_json") private String payloadJson;
    @Column(name = "fetched_at") private Instant fetchedAt = Instant.now();

    protected DataSnapshot() {}

    public DataSnapshot(Long applicationId, String kind, String vendor, String payloadJson) {
        this.applicationId = applicationId;
        this.kind = kind;
        this.vendor = vendor;
        this.payloadJson = payloadJson;
    }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getKind() { return kind; }
    public String getVendor() { return vendor; }
    public String getPayloadJson() { return payloadJson; }
    public Instant getFetchedAt() { return fetchedAt; }
}
