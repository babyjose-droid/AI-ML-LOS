package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A request to release or swap a collateral on a live loan: maker, checker, then operations hands the documents back. */
@Entity
@Table(name = "collateral_change")
public class CollateralChange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    @Column(name = "application_id") private Long applicationId;
    private String kind;
    @Column(name = "collateral_id") private Long collateralId;
    @Column(name = "new_collateral_id") private Long newCollateralId;
    private String reason;
    @Column(name = "outstanding_at") private BigDecimal outstandingAt;
    @Column(name = "ltv_before") private BigDecimal ltvBefore;
    @Column(name = "ltv_after") private BigDecimal ltvAfter;
    @Column(name = "check_note") private String checkNote;
    private String status = "REQUESTED";
    @Column(name = "due_on") private LocalDate dueOn;
    @Column(name = "requested_by") private String requestedBy;
    @Column(name = "requested_at") private Instant requestedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note") private String decisionNote;
    @Column(name = "completed_by") private String completedBy;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "handed_to") private String handedTo;
    @Column(name = "days_late") private Integer daysLate;
    private BigDecimal compensation;
    private String letter;

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long v) { this.loanId = v; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public Long getCollateralId() { return collateralId; }
    public void setCollateralId(Long v) { this.collateralId = v; }
    public Long getNewCollateralId() { return newCollateralId; }
    public void setNewCollateralId(Long v) { this.newCollateralId = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public BigDecimal getOutstandingAt() { return outstandingAt; }
    public void setOutstandingAt(BigDecimal v) { this.outstandingAt = v; }
    public BigDecimal getLtvBefore() { return ltvBefore; }
    public void setLtvBefore(BigDecimal v) { this.ltvBefore = v; }
    public BigDecimal getLtvAfter() { return ltvAfter; }
    public void setLtvAfter(BigDecimal v) { this.ltvAfter = v; }
    public String getCheckNote() { return checkNote; }
    public void setCheckNote(String v) { this.checkNote = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDate getDueOn() { return dueOn; }
    public void setDueOn(LocalDate v) { this.dueOn = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }
    public String getDecidedBy() { return decidedBy; }
    public void setDecidedBy(String v) { this.decidedBy = v; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant v) { this.decidedAt = v; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String v) { this.decisionNote = v; }
    public String getCompletedBy() { return completedBy; }
    public void setCompletedBy(String v) { this.completedBy = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public String getHandedTo() { return handedTo; }
    public void setHandedTo(String v) { this.handedTo = v; }
    public Integer getDaysLate() { return daysLate; }
    public void setDaysLate(Integer v) { this.daysLate = v; }
    public BigDecimal getCompensation() { return compensation; }
    public void setCompensation(BigDecimal v) { this.compensation = v; }
    public String getLetter() { return letter; }
    public void setLetter(String v) { this.letter = v; }
}
