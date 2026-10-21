package com.rhythm.los.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** The part of a receivables limit that may be used on one buyer. */
@Entity
@Table(name = "trade_sublimit")
public class TradeSublimit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "limit_id") public Long limitId;
    @Column(name = "counterparty_id") public Long counterpartyId;
    public BigDecimal amount;
}
