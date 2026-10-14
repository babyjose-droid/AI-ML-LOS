package com.rhythm.los.growth;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "loan_restructure")
public class LoanRestructure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    private String kind;
    @Column(name = "new_tenure_months") private int newTenureMonths;
    @Column(name = "new_rate") private BigDecimal newRate;
    @Column(name = "moratorium_months") private int moratoriumMonths;
    @Column(name = "new_emi") private BigDecimal newEmi;
    @Column(name = "outstanding_at") private BigDecimal outstandingAt;
    private String reason;
    private String status = "PENDING";
    @Column(name = "requested_by") private String requestedBy;
    @Column(name = "requested_at") private Instant requestedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_note") private String decisionNote;
    @Column(name = "schedule_json") private String scheduleJson;

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public void setLoanId(Long v) { this.loanId = v; }
    public String getKind() { return kind; }
    public void setKind(String v) { this.kind = v; }
    public int getNewTenureMonths() { return newTenureMonths; }
    public void setNewTenureMonths(int v) { this.newTenureMonths = v; }
    public BigDecimal getNewRate() { return newRate; }
    public void setNewRate(BigDecimal v) { this.newRate = v; }
    public int getMoratoriumMonths() { return moratoriumMonths; }
    public void setMoratoriumMonths(int v) { this.moratoriumMonths = v; }
    public BigDecimal getNewEmi() { return newEmi; }
    public void setNewEmi(BigDecimal v) { this.newEmi = v; }
    public BigDecimal getOutstandingAt() { return outstandingAt; }
    public void setOutstandingAt(BigDecimal v) { this.outstandingAt = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
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
    public String getScheduleJson() { return scheduleJson; }
    public void setScheduleJson(String v) { this.scheduleJson = v; }
}
