package com.rhythm.los.plan;

import jakarta.persistence.*;
import java.time.Instant;

/** What a housing, education or farm loan pays for, and the checks on it. */
@Entity
@Table(name = "loan_plan")
public class LoanPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "application_id") public Long applicationId;
    public String kind;
    @Column(name = "details_json") public String detailsJson = "{}";
    @Column(name = "checks_json") public String checksJson;
    @Column(name = "rulings_json") public String rulingsJson;
    public String status = "PENDING";
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
