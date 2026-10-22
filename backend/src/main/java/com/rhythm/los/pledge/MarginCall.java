package com.rhythm.los.pledge;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The loan is above the drawing power: the borrower pledges more or pays down by the due date, or the pledge is invoked. */
@Entity
@Table(name = "margin_call")
public class MarginCall {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "pledge_id") public Long pledgeId;
    @Column(name = "application_id") public Long applicationId;
    public BigDecimal outstanding;
    @Column(name = "drawing_power") public BigDecimal drawingPower;
    public BigDecimal shortfall;
    @Column(name = "due_by") public LocalDate dueBy;
    public String status = "OPEN";
    @Column(name = "cure_note") public String cureNote;
    @Column(name = "closed_by") public String closedBy;
    @Column(name = "closed_at") public Instant closedAt;
    @Column(name = "created_at") public Instant createdAt = Instant.now();
}
