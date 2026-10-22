package com.rhythm.los.pledge;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Securities or a fixed deposit pledged for a loan, the lien and the drawing power. */
@Entity
@Table(name = "pledge")
public class Pledge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "application_id") public Long applicationId;
    public String kind;
    @Column(name = "holder_name") public String holderName;
    public String depository;
    @Column(name = "demat_id") public String dematId;
    public String issuer;
    @Column(name = "deposit_no") public String depositNo;
    public BigDecimal principal;
    @Column(name = "rate_pa") public BigDecimal ratePa;
    @Column(name = "start_date") public LocalDate startDate;
    @Column(name = "maturity_date") public LocalDate maturityDate;
    @Column(name = "lien_state") public String lienState = "NONE";
    @Column(name = "lien_ref") public String lienRef;
    @Column(name = "lien_marked_by") public String lienMarkedBy;
    @Column(name = "lien_marked_at") public Instant lienMarkedAt;
    public BigDecimal value;
    @Column(name = "drawing_power") public BigDecimal drawingPower;
    public String status = "PENDING";
    @Column(name = "checks_json") public String checksJson;
    @Column(name = "rulings_json") public String rulingsJson;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
