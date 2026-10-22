package com.rhythm.los.pledge;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "pledge_holding")
public class PledgeHolding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "pledge_id") public Long pledgeId;
    public String isin;
    public String name;
    public BigDecimal quantity;
    @Column(name = "added_for_call") public Long addedForCall;
}
