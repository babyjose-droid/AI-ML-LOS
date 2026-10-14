package com.rhythm.los.party;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "party_requirement")
public class PartyRequirement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "role") private String role;
    @Column(name = "reason") private String reason;
    @Column(name = "status") private String status = "OPEN";
    @Column(name = "requested_by") private String requestedBy;
    @Column(name = "requested_at") private Instant requestedAt = Instant.now();
    @Column(name = "met_by_party_id") private Long metByPartyId;
    @Column(name = "closed_by") private String closedBy;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "close_note") private String closeNote;

    public Long getId() { return id; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public Long getMetByPartyId() { return metByPartyId; }
    public void setMetByPartyId(Long v) { this.metByPartyId = v; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String v) { this.closedBy = v; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant v) { this.closedAt = v; }
    public String getCloseNote() { return closeNote; }
    public void setCloseNote(String v) { this.closeNote = v; }
}
