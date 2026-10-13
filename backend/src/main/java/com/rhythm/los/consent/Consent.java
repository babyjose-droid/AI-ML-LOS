package com.rhythm.los.consent;

import jakarta.persistence.*;

import java.time.Instant;

/** One consent for one data source (FR-KYC-01): purpose, scope, validity, channel and language; withdrawal is recorded, never deleted. */
@Entity
@Table(name = "consent")
public class Consent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    /** AA, BUREAU, KYC, GST, COMMUNICATION */
    private String source;
    private String purpose;
    private String scope;
    private String channel;
    private String language;
    /** GRANTED, WITHDRAWN (expiry is read from valid_until) */
    private String status = "GRANTED";
    @Column(name = "granted_at") private Instant grantedAt = Instant.now();
    @Column(name = "valid_until") private Instant validUntil;
    @Column(name = "withdrawn_at") private Instant withdrawnAt;
    @Column(name = "withdrawn_by") private String withdrawnBy;
    private String effect;

    protected Consent() {}

    public Consent(Long applicationId, String source, String purpose, String scope, String channel, String language, Instant validUntil) {
        this.applicationId = applicationId;
        this.source = source;
        this.purpose = purpose;
        this.scope = scope;
        this.channel = channel;
        this.language = language;
        this.validUntil = validUntil;
    }

    /** Usable now: granted and not past its validity. */
    public boolean valid() { return "GRANTED".equals(status) && (validUntil == null || validUntil.isAfter(Instant.now())); }

    @Transient
    public String getState() { return "WITHDRAWN".equals(status) ? "WITHDRAWN" : valid() ? "ACTIVE" : "EXPIRED"; }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public String getSource() { return source; }
    public String getPurpose() { return purpose; }
    public String getScope() { return scope; }
    public String getChannel() { return channel; }
    public String getLanguage() { return language; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getGrantedAt() { return grantedAt; }
    public Instant getValidUntil() { return validUntil; }
    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant v) { this.withdrawnAt = v; }
    public String getWithdrawnBy() { return withdrawnBy; }
    public void setWithdrawnBy(String v) { this.withdrawnBy = v; }
    public String getEffect() { return effect; }
    public void setEffect(String v) { this.effect = v; }
}
