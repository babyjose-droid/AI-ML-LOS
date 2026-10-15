package com.rhythm.los.growth;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "collection_contact")
public class CollectionContact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    private String channel;
    private String outcome;
    @Column(name = "contacted_at") private Instant contactedAt;
    @Column(name = "dpd_at_contact") private int dpdAtContact;
    @Column(name = "ptp_date") private LocalDate ptpDate;
    @Column(name = "ptp_amount") private BigDecimal ptpAmount;
    @Column(name = "ptp_status") private String ptpStatus;
    private String note;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "party_id") private Long partyId;
    @Column(name = "contact_name") private String contactName;

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long v) { this.loanId = v; }
    public String getChannel() { return channel; }
    public void setChannel(String v) { this.channel = v; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String v) { this.outcome = v; }
    public Instant getContactedAt() { return contactedAt; }
    public void setContactedAt(Instant v) { this.contactedAt = v; }
    public int getDpdAtContact() { return dpdAtContact; }
    public void setDpdAtContact(int v) { this.dpdAtContact = v; }
    public LocalDate getPtpDate() { return ptpDate; }
    public void setPtpDate(LocalDate v) { this.ptpDate = v; }
    public BigDecimal getPtpAmount() { return ptpAmount; }
    public void setPtpAmount(BigDecimal v) { this.ptpAmount = v; }
    public String getPtpStatus() { return ptpStatus; }
    public void setPtpStatus(String v) { this.ptpStatus = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public Long getPartyId() { return partyId; }
    public void setPartyId(Long v) { this.partyId = v; }
    public String getContactName() { return contactName; }
    public void setContactName(String v) { this.contactName = v; }
}
