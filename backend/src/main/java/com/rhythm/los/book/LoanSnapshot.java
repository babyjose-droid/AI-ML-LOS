package com.rhythm.los.book;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(name = "loan_snapshot")
public class LoanSnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_id") private Long loanId;
    @Column(name = "feed_date") private LocalDate feedDate;
    private BigDecimal outstanding;
    private BigDecimal overdue;
    private int dpd;
    private String classification;
    @Column(name = "ecl_stage") private int eclStage;
    private BigDecimal ecl;

    protected LoanSnapshot() {}

    public LoanSnapshot(LiveLoan l, LocalDate on) {
        this.loanId = l.getId();
        this.feedDate = on;
        this.outstanding = l.getOutstanding();
        this.overdue = l.getOverdue();
        this.dpd = l.getDpd();
        this.classification = l.getClassification();
        this.eclStage = l.getEclStage();
        this.ecl = l.getEcl();
    }

    public Long getId() { return id; }
    public Long getLoanId() { return loanId; }
    public LocalDate getFeedDate() { return feedDate; }
    public BigDecimal getOutstanding() { return outstanding; }
    public BigDecimal getOverdue() { return overdue; }
    public int getDpd() { return dpd; }
    public String getClassification() { return classification; }
    public int getEclStage() { return eclStage; }
    public BigDecimal getEcl() { return ecl; }
}
