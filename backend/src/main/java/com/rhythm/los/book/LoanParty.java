package com.rhythm.los.book;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** A co-applicant or guarantor on a live loan: whom collections may contact and how the loan is reported to the credit bureaus. */
@Entity
@Table(name = "loan_party")
public class LoanParty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    @Column(name = "application_party_id") private Long applicationPartyId;
    private String role;
    @Column(name = "party_type") private String partyType = "INDIVIDUAL";
    private String name;
    private String pan;
    private String mobile;
    private LocalDate dob;
    private String relationship;
    @Column(name = "signatory_name") private String signatoryName;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long v) { this.loanId = v; }
    public Long getApplicationPartyId() { return applicationPartyId; }
    public void setApplicationPartyId(Long v) { this.applicationPartyId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getPartyType() { return partyType; }
    public void setPartyType(String v) { this.partyType = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { this.pan = v; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate v) { this.dob = v; }
    public String getRelationship() { return relationship; }
    public void setRelationship(String v) { this.relationship = v; }
    public String getSignatoryName() { return signatoryName; }
    public void setSignatoryName(String v) { this.signatoryName = v; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean guarantor() { return "GUARANTOR".equals(role); }
}
