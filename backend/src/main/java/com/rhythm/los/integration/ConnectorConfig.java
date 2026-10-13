package com.rhythm.los.integration;

import jakarta.persistence.*;
import java.time.Instant;

/** Per-connector settings (SCR-130): mode, on/off, timeout, retries and circuit-breaker limits. */
@Entity
@Table(name = "connector_config")
public class ConnectorConfig {
    @Id private String connector;
    private String name;
    private String mode = "MOCK";
    private boolean enabled = true;
    @Column(name = "timeout_ms") private int timeoutMs = 10000;
    @Column(name = "max_attempts") private int maxAttempts = 3;
    @Column(name = "breaker_threshold") private int breakerThreshold = 5;
    @Column(name = "breaker_cooldown_s") private int breakerCooldownS = 60;
    @Column(name = "base_url") private String baseUrl;
    private String notes;
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public String getConnector() { return connector; }
    public String getName() { return name; }
    public String getMode() { return mode; }
    public boolean isEnabled() { return enabled; }
    public int getTimeoutMs() { return timeoutMs; }
    public int getMaxAttempts() { return maxAttempts; }
    public int getBreakerThreshold() { return breakerThreshold; }
    public int getBreakerCooldownS() { return breakerCooldownS; }
    public String getBaseUrl() { return baseUrl; }
    public String getNotes() { return notes; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setEnabled(boolean v) { this.enabled = v; }
    public void setTimeoutMs(int v) { this.timeoutMs = v; }
    public void setMaxAttempts(int v) { this.maxAttempts = v; }
    public void setBreakerThreshold(int v) { this.breakerThreshold = v; }
    public void setBreakerCooldownS(int v) { this.breakerCooldownS = v; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public void setNotes(String v) { this.notes = v; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
