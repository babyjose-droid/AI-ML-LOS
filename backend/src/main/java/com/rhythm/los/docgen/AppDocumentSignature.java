package com.rhythm.los.docgen;

import jakarta.persistence.*;
import java.time.Instant;

/** A signature on a generated document. */
@Entity
@Table(name = "loan_doc_signature")
public class AppDocumentSignature {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_doc_id") private Long appDocumentId;
    @Column(name = "signer_role") private String signerRole;
    @Column(name = "party_id") private Long partyId;
    @Column(name = "signer_name") private String signerName;
    private String channel;
    @Column(name = "doc_sha256") private String docSha256;
    @Column(name = "recorded_by") private String recordedBy;
    @Column(name = "signed_at") private Instant signedAt = Instant.now();

    public Long getId() { return id; }
    public Long getAppDocumentId() { return appDocumentId; }
    public void setAppDocumentId(Long v) { this.appDocumentId = v; }
    public String getSignerRole() { return signerRole; }
    public void setSignerRole(String v) { this.signerRole = v; }
    public Long getPartyId() { return partyId; }
    public void setPartyId(Long v) { this.partyId = v; }
    public String getSignerName() { return signerName; }
    public void setSignerName(String v) { this.signerName = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getDocSha256() { return docSha256; }
    public void setDocSha256(String v) { this.docSha256 = v; }
    public String getRecordedBy() { return recordedBy; }
    public void setRecordedBy(String v) { this.recordedBy = v; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant v) { this.signedAt = v; }
}
