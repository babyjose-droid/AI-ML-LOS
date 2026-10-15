package com.rhythm.los.vkyc;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A video KYC (V-CIP) session. The customer joins by a one-time link; an operations official runs the call and decides. */
@Entity
@Table(name = "vkyc_session")
public class VkycSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "application_id") private Long applicationId;
    @JsonIgnore @Column(name = "token_hash") private String tokenHash;
    private String status;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "joined_at") private Instant joinedAt;
    @Column(name = "consent_at") private Instant consentAt;
    @Column(name = "customer_lat") private BigDecimal customerLat;
    @Column(name = "customer_lng") private BigDecimal customerLng;
    @Column(name = "customer_accuracy_m") private BigDecimal customerAccuracyM;
    @Column(name = "in_india") private Boolean inIndia;
    @JsonIgnore @Column(name = "customer_ip") private String customerIp;
    @Column(name = "customer_agent") private String customerAgent;
    private String officer;
    @Column(name = "call_started_at") private Instant callStartedAt;
    @Column(name = "call_ended_at") private Instant callEndedAt;
    @Column(name = "challenge_code") private String challengeCode;
    @JsonIgnore @Column(name = "frames_json") private String framesJson;
    @JsonIgnore @Column(name = "recording_path") private String recordingPath;
    @Column(name = "recording_sha256") private String recordingSha256;
    @Column(name = "recording_bytes") private Long recordingBytes;
    @Column(name = "ai_status") private String aiStatus;
    @JsonIgnore @Column(name = "analysis_json") private String analysisJson;
    private String decision;
    @Column(name = "decision_note") private String decisionNote;
    @Column(name = "code_heard") private Boolean codeHeard;
    @Column(name = "ai_override") private boolean aiOverride;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    /** The co-applicant or guarantor on the call; null for the applicant. */
    @Column(name = "party_id") private Long partyId;
    @Column(name = "person_name") private String personName;
    @Column(name = "ip_country") private String ipCountry;
    @Column(name = "slot_at") private Instant slotAt;
    @Column(name = "booked_by") private String bookedBy;
    @Column(name = "booked_at") private Instant bookedAt;
    /** The product's video KYC settings when the link was made (later product changes do not apply to it). */
    @JsonIgnore @Column(name = "policy_json") private String policyJson;
    /** The questions asked and whether each was answered satisfactorily. */
    @Column(name = "questions_json") private String questionsJson;

    public Long getPartyId() { return partyId; }
    public void setPartyId(Long v) { this.partyId = v; }
    public String getPersonName() { return personName; }
    public void setPersonName(String v) { this.personName = v; }
    @JsonIgnore public String getPolicyJson() { return policyJson; }
    public void setPolicyJson(String v) { this.policyJson = v; }
    public String getQuestionsJson() { return questionsJson; }
    public void setQuestionsJson(String v) { this.questionsJson = v; }

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant v) { this.joinedAt = v; }
    public Instant getConsentAt() { return consentAt; }
    public void setConsentAt(Instant v) { this.consentAt = v; }
    public BigDecimal getCustomerLat() { return customerLat; }
    public void setCustomerLat(BigDecimal v) { this.customerLat = v; }
    public BigDecimal getCustomerLng() { return customerLng; }
    public void setCustomerLng(BigDecimal v) { this.customerLng = v; }
    public BigDecimal getCustomerAccuracyM() { return customerAccuracyM; }
    public void setCustomerAccuracyM(BigDecimal v) { this.customerAccuracyM = v; }
    public Boolean getInIndia() { return inIndia; }
    public void setInIndia(Boolean v) { this.inIndia = v; }
    public String getCustomerIp() { return customerIp; }
    public void setCustomerIp(String v) { this.customerIp = v; }
    public String getCustomerAgent() { return customerAgent; }
    public void setCustomerAgent(String v) { this.customerAgent = v; }
    public String getOfficer() { return officer; }
    public void setOfficer(String v) { this.officer = v; }
    public Instant getCallStartedAt() { return callStartedAt; }
    public void setCallStartedAt(Instant v) { this.callStartedAt = v; }
    public Instant getCallEndedAt() { return callEndedAt; }
    public void setCallEndedAt(Instant v) { this.callEndedAt = v; }
    public String getChallengeCode() { return challengeCode; }
    public void setChallengeCode(String v) { this.challengeCode = v; }
    public String getFramesJson() { return framesJson; }
    public void setFramesJson(String v) { this.framesJson = v; }
    public String getRecordingPath() { return recordingPath; }
    public void setRecordingPath(String v) { this.recordingPath = v; }
    public String getRecordingSha256() { return recordingSha256; }
    public void setRecordingSha256(String v) { this.recordingSha256 = v; }
    public Long getRecordingBytes() { return recordingBytes; }
    public void setRecordingBytes(Long v) { this.recordingBytes = v; }
    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String v) { this.aiStatus = v; }
    public String getAnalysisJson() { return analysisJson; }
    public void setAnalysisJson(String v) { this.analysisJson = v; }
    public String getDecision() { return decision; }
    public void setDecision(String v) { this.decision = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
    public Boolean getCodeHeard() { return codeHeard; }
    public void setCodeHeard(Boolean v) { this.codeHeard = v; }
    public boolean isAiOverride() { return aiOverride; }
    public void setAiOverride(boolean v) { this.aiOverride = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public boolean isHasRecording() { return recordingPath != null; }
    public String getIpCountry() { return ipCountry; }
    public void setIpCountry(String v) { this.ipCountry = v; }
    public Instant getSlotAt() { return slotAt; }
    public void setSlotAt(Instant v) { this.slotAt = v; }
    public String getBookedBy() { return bookedBy; }
    public void setBookedBy(String v) { this.bookedBy = v; }
    public Instant getBookedAt() { return bookedAt; }
    public void setBookedAt(Instant v) { this.bookedAt = v; }
}
