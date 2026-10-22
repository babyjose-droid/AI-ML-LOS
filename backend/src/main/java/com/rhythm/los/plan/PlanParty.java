package com.rhythm.los.plan;

import jakarta.persistence.*;
import java.time.Instant;

/** An approved institution (education) or builder (housing) that tranches can be paid to. */
@Entity
@Table(name = "plan_party")
public class PlanParty {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    public String code;
    public String name;
    public String kind;
    public String category;
    public String accreditation;
    public String gstin;
    public String city;
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
