package com.rhythm.los.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The revolving limit of one application: proposed before sanction, active after activation. */
@Entity
@Table(name = "trade_limit")
public class TradeLimit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "limit_no") public String limitNo;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "product_code") public String productCode;
    @Column(name = "anchor_id") public Long anchorId;
    @Column(name = "borrower_gstin") public String borrowerGstin;
    @Column(name = "sanctioned_limit") public BigDecimal sanctionedLimit;
    public String status = "PROPOSED";
    @Column(name = "valid_from") public LocalDate validFrom;
    @Column(name = "valid_to") public LocalDate validTo;
    @Column(name = "rate_pa") public BigDecimal ratePa;
    @Column(name = "structure_ok") public boolean structureOk;
    @Column(name = "checks_json") public String checksJson;
    @Column(name = "frozen_reason") public String frozenReason;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
