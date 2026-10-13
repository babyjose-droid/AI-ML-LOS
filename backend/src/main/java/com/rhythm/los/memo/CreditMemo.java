package com.rhythm.los.memo;

import jakarta.persistence.*;

import java.time.Instant;

/** One version of an application's credit memo. Signed versions are locked (FR-CAM-03). */
@Entity
@Table(name = "credit_memo")
public class CreditMemo {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "decision_id") private Long decisionId;
    private int version;
    /** DRAFT, SIGNED, SUPERSEDED */
    private String status;
    private String body;
    @Column(name = "deviations_json") private String deviationsJson;
    @Column(name = "edited_by") private String editedBy;
    @Column(name = "edited_at") private Instant editedAt = Instant.now();
    @Column(name = "signed_by") private String signedBy;
    @Column(name = "signer_role") private String signerRole;
    @Column(name = "signed_at") private Instant signedAt;

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public Long getDecisionId() { return decisionId; }
    public void setDecisionId(Long v) { this.decisionId = v; }
    public int getVersion() { return version; }
    public void setVersion(int v) { this.version = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getDeviationsJson() { return deviationsJson; }
    public void setDeviationsJson(String v) { this.deviationsJson = v; }
    public String getEditedBy() { return editedBy; }
    public void setEditedBy(String v) { this.editedBy = v; }
    public Instant getEditedAt() { return editedAt; }
    public void setEditedAt(Instant v) { this.editedAt = v; }
    public String getSignedBy() { return signedBy; }
    public void setSignedBy(String v) { this.signedBy = v; }
    public String getSignerRole() { return signerRole; }
    public void setSignerRole(String v) { this.signerRole = v; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant v) { this.signedAt = v; }
}
