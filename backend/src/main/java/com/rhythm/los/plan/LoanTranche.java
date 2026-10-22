package com.rhythm.los.plan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** One release of a housing or education loan: a construction stage or a term's fee. */
@Entity
@Table(name = "loan_tranche")
public class LoanTranche {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "application_id") public Long applicationId;
    public int seq;
    public String label;
    public BigDecimal amount;
    @Column(name = "stage_pct") public BigDecimal stagePct;
    @Column(name = "payee_kind") public String payeeKind = "BORROWER";
    @Column(name = "payee_id") public Long payeeId;
    public String status = "PLANNED";
    @Column(name = "certified_pct") public BigDecimal certifiedPct;
    @Column(name = "certified_by") public String certifiedBy;
    @Column(name = "certified_at") public Instant certifiedAt;
    @Column(name = "certificate_note") public String certificateNote;
    @Column(name = "paid_amount") public BigDecimal paidAmount;
    @Column(name = "paid_by") public String paidBy;
    @Column(name = "paid_at") public Instant paidAt;
    public String utr;
    @Column(name = "paid_to") public String paidTo;
}
