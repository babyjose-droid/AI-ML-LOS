package com.rhythm.los.asset;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** A dealer, supplier, OEM or installer the lender pays for the asset it finances. */
@Entity
@Table(name = "dealer")
public class Dealer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String code;
    public String name;
    public String kind;
    public String categories;
    public String oem;
    public String gstin;
    public String pan;
    public String city;
    public String pincode;
    @Column(name = "account_name") public String accountName;
    @Column(name = "account_no") public String accountNo;
    public String ifsc;
    public boolean empanelled = true;
    public String status = "ACTIVE";
    @Column(name = "max_exposure") public BigDecimal maxExposure;
    public String notes;
    @Column(name = "created_by") public String createdBy = "system";
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
