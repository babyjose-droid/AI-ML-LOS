package com.rhythm.los.party;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "application_party")
public class ApplicationParty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "party_no") private String partyNo;
    @Column(name = "role") private String role;
    @Column(name = "relationship") private String relationship;
    @Column(name = "name") private String name;
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = com.rhythm.los.common.Mask.PanSerializer.class) @Column(name = "pan") private String pan;
    @Column(name = "mobile") private String mobile;
    @Column(name = "email") private String email;
    @Column(name = "dob") private LocalDate dob;
    @Column(name = "gender") private String gender;
    @Column(name = "address") private String address;
    @Column(name = "city") private String city;
    @Column(name = "pincode") private String pincode;
    @Column(name = "occupation") private String occupation;
    @Column(name = "monthly_income") private BigDecimal monthlyIncome;
    @Column(name = "existing_emi") private BigDecimal existingEmi;
    @Column(name = "income_considered") private boolean incomeConsidered;
    @Column(name = "co_owner") private boolean coOwner;
    @Column(name = "status") private String status = "ADDED";
    @Column(name = "added_stage") private String addedStage = "APPLICATION";
    @Column(name = "requirement_id") private Long requirementId;
    @Column(name = "consent_kyc") private boolean consentKyc;
    @Column(name = "consent_bureau") private boolean consentBureau;
    @Column(name = "consent_at") private Instant consentAt;
    @Column(name = "consent_channel") private String consentChannel;
    @Column(name = "consent_language") private String consentLanguage;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "invite_hash") private String inviteHash;
    @Column(name = "invite_expires_at") private Instant inviteExpiresAt;
    @Column(name = "invited_at") private Instant invitedAt;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "otp_hash") private String otpHash;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "otp_expires_at") private Instant otpExpiresAt;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "otp_attempts") private int otpAttempts;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "session_hash") private String sessionHash;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "session_expires_at") private Instant sessionExpiresAt;
    @Column(name = "kyc_state") private String kycState = "NOT_STARTED";
    @Column(name = "kyc_score") private BigDecimal kycScore;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "kyc_json") private String kycJson;
    @Column(name = "kyc_note") private String kycNote;
    @Column(name = "kyc_reviewed_by") private String kycReviewedBy;
    @Column(name = "bureau_state") private String bureauState = "NOT_FETCHED";
    @Column(name = "bureau_score") private Integer bureauScore;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "bureau_json") private String bureauJson;
    @Column(name = "signed_at") private Instant signedAt;
    @Column(name = "signed_channel") private String signedChannel;
    @Column(name = "signed_document") private String signedDocument;
    @Column(name = "removed_reason") private String removedReason;
    @Column(name = "added_by") private String addedBy;
    @Column(name = "added_at") private Instant addedAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getPartyNo() { return partyNo; }
    public void setPartyNo(String v) { this.partyNo = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String v) { this.relationship = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { this.pan = v; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate v) { this.dob = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getPincode() { return pincode; }
    public void setPincode(String v) { this.pincode = v; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String v) { this.occupation = v; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal v) { this.monthlyIncome = v; }
    public BigDecimal getExistingEmi() { return existingEmi; }
    public void setExistingEmi(BigDecimal v) { this.existingEmi = v; }
    public boolean isIncomeConsidered() { return incomeConsidered; }
    public void setIncomeConsidered(boolean v) { this.incomeConsidered = v; }
    public boolean isCoOwner() { return coOwner; }
    public void setCoOwner(boolean v) { this.coOwner = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getAddedStage() { return addedStage; }
    public void setAddedStage(String v) { this.addedStage = v; }
    public Long getRequirementId() { return requirementId; }
    public void setRequirementId(Long v) { this.requirementId = v; }
    public boolean isConsentKyc() { return consentKyc; }
    public void setConsentKyc(boolean v) { this.consentKyc = v; }
    public boolean isConsentBureau() { return consentBureau; }
    public void setConsentBureau(boolean v) { this.consentBureau = v; }
    public Instant getConsentAt() { return consentAt; }
    public void setConsentAt(Instant v) { this.consentAt = v; }
    public String getConsentChannel() { return consentChannel; }
    public void setConsentChannel(String v) { this.consentChannel = v; }
    public String getConsentLanguage() { return consentLanguage; }
    public void setConsentLanguage(String v) { this.consentLanguage = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getInviteHash() { return inviteHash; }
    public void setInviteHash(String v) { this.inviteHash = v; }
    public Instant getInviteExpiresAt() { return inviteExpiresAt; }
    public void setInviteExpiresAt(Instant v) { this.inviteExpiresAt = v; }
    public Instant getInvitedAt() { return invitedAt; }
    public void setInvitedAt(Instant v) { this.invitedAt = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getOtpHash() { return otpHash; }
    public void setOtpHash(String v) { this.otpHash = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public Instant getOtpExpiresAt() { return otpExpiresAt; }
    public void setOtpExpiresAt(Instant v) { this.otpExpiresAt = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public int getOtpAttempts() { return otpAttempts; }
    public void setOtpAttempts(int v) { this.otpAttempts = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getSessionHash() { return sessionHash; }
    public void setSessionHash(String v) { this.sessionHash = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public Instant getSessionExpiresAt() { return sessionExpiresAt; }
    public void setSessionExpiresAt(Instant v) { this.sessionExpiresAt = v; }
    public String getKycState() { return kycState; }
    public void setKycState(String v) { this.kycState = v; }
    public BigDecimal getKycScore() { return kycScore; }
    public void setKycScore(BigDecimal v) { this.kycScore = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getKycJson() { return kycJson; }
    public void setKycJson(String v) { this.kycJson = v; }
    public String getKycNote() { return kycNote; }
    public void setKycNote(String v) { this.kycNote = v; }
    public String getKycReviewedBy() { return kycReviewedBy; }
    public void setKycReviewedBy(String v) { this.kycReviewedBy = v; }
    public String getBureauState() { return bureauState; }
    public void setBureauState(String v) { this.bureauState = v; }
    public Integer getBureauScore() { return bureauScore; }
    public void setBureauScore(Integer v) { this.bureauScore = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public String getBureauJson() { return bureauJson; }
    public void setBureauJson(String v) { this.bureauJson = v; }
    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant v) { this.signedAt = v; }
    public String getSignedChannel() { return signedChannel; }
    public void setSignedChannel(String v) { this.signedChannel = v; }
    public String getSignedDocument() { return signedDocument; }
    public void setSignedDocument(String v) { this.signedDocument = v; }
    public String getRemovedReason() { return removedReason; }
    public void setRemovedReason(String v) { this.removedReason = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public Instant getAddedAt() { return addedAt; }
    public void setAddedAt(Instant v) { this.addedAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    /** The mobile with the middle digits hidden, for screens and the invite page. */
    public String maskedMobile() { return mobile == null || mobile.length() < 10 ? mobile : mobile.substring(0, 2) + "xxxxxx" + mobile.substring(8); }
    public boolean active() { return !"REMOVED".equals(status); }
}
