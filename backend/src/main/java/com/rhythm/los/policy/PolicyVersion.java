package com.rhythm.los.policy;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "policy_version")
public class PolicyVersion {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String version;
    private String status;
    @Column(name = "params_json") private String paramsJson;
    private String notes;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getVersion() { return version; }
    public String getStatus() { return status; }
    public String getParamsJson() { return paramsJson; }
    public String getNotes() { return notes; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
