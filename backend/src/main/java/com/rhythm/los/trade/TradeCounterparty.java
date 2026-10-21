package com.rhythm.los.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** An anchor running a vendor or dealer programme, or a buyer whose invoices are financed. */
@Entity
@Table(name = "trade_counterparty")
public class TradeCounterparty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String code;
    public String name;
    public String kind;
    public String programme;
    public String gstin;
    public String pan;
    public String city;
    public String industry;
    public String rating;
    @Column(name = "programme_limit") public BigDecimal programmeLimit;
    @Column(name = "max_tenor_days") public int maxTenorDays = 90;
    @Column(name = "advance_rate") public BigDecimal advanceRate = new BigDecimal("0.80");
    @Column(name = "grace_days") public int graceDays = 7;
    public String recourse = "WITH";
    @Column(name = "fldg_pct") public BigDecimal fldgPct;
    @Column(name = "confirmation_required") public boolean confirmationRequired = true;
    @Column(name = "concentration_cap") public BigDecimal concentrationCap = new BigDecimal("0.60");
    @Column(name = "repaid_by") public String repaidBy = "BUYER";
    @Column(name = "account_name") public String accountName;
    @Column(name = "account_no") @com.fasterxml.jackson.annotation.JsonIgnore public String accountNo;
    public String ifsc;
    public boolean empanelled = true;
    public String status = "ACTIVE";
    public String notes;
    @Column(name = "created_by") public String createdBy = "system";
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
