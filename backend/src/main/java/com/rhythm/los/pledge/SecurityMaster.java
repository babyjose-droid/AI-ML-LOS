package com.rhythm.los.pledge;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** An approved security, its LTV and the latest price (from the daily price file). */
@Entity
@Table(name = "security_master")
public class SecurityMaster {
    @Id public String isin;
    public String name;
    public String category;
    public boolean approved = true;
    @Column(name = "max_ltv") public BigDecimal maxLtv;
    public BigDecimal price;
    @Column(name = "price_date") public LocalDate priceDate;
    @Column(name = "updated_by") public String updatedBy = "system";
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
