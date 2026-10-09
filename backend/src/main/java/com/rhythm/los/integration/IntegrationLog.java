package com.rhythm.los.integration;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "integration_log")
public class IntegrationLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    private String vendor;
    private String operation;
    private int attempt;
    private String status;
    @Column(name = "latency_ms") private long latencyMs;
    @Column(name = "request_ref") private String requestRef;
    @Column(name = "response_summary") private String responseSummary;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "retry_of") private Long retryOf;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected IntegrationLog() {}

    public IntegrationLog(Long applicationId, String vendor, String operation, int attempt, String status, long latencyMs,
                          String requestRef, String responseSummary, String errorMessage, Long retryOf) {
        this.applicationId = applicationId;
        this.vendor = vendor;
        this.operation = operation;
        this.attempt = attempt;
        this.status = status;
        this.latencyMs = latencyMs;
        this.requestRef = requestRef;
        this.responseSummary = cut(responseSummary);
        this.errorMessage = cut(errorMessage);
        this.retryOf = retryOf;
    }

    private static String cut(String s) { return s == null ? null : (s.length() > 500 ? s.substring(0, 500) : s); }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getVendor() { return vendor; }
    public String getOperation() { return operation; }
    public int getAttempt() { return attempt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getLatencyMs() { return latencyMs; }
    public String getRequestRef() { return requestRef; }
    public String getResponseSummary() { return responseSummary; }
    public String getErrorMessage() { return errorMessage; }
    public Long getRetryOf() { return retryOf; }
    public Instant getCreatedAt() { return createdAt; }
}
