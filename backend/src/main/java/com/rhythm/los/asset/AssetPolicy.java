package com.rhythm.los.asset;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

/** The rules for one asset class: LTV, minimum down payment, depreciation, insurance, registration and post-disbursal documents. */
@Entity
@Table(name = "asset_policy")
public class AssetPolicy {
    @Id @Column(name = "asset_class") public String assetClass;
    public String label;
    @Column(name = "max_ltv") public BigDecimal maxLtv;
    @Column(name = "min_down_pct") public BigDecimal minDownPct;
    @Column(name = "dep_rate") public BigDecimal depRate;
    @Column(name = "max_age_years") public int maxAgeYears;
    @Column(name = "insurance_required") public boolean insuranceRequired;
    @Column(name = "registration_required") public boolean registrationRequired;
    @Column(name = "serial_label") public String serialLabel;
    @Column(name = "quote_variance") public BigDecimal quoteVariance;
    @Column(name = "fleet_limit") public BigDecimal fleetLimit;
    @Column(name = "post_docs") public String postDocs;
    @Column(name = "post_days") public int postDays;
    @Column(name = "updated_by") public String updatedBy;
    @Column(name = "updated_at") public Instant updatedAt;

    AssetRules.Policy rules() {
        return new AssetRules.Policy(assetClass, label, maxLtv.doubleValue(), minDownPct.doubleValue(), depRate.doubleValue(), maxAgeYears,
                insuranceRequired, registrationRequired, serialLabel, quoteVariance.doubleValue(), fleetLimit == null ? null : fleetLimit.doubleValue(),
                java.util.Arrays.stream(postDocs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList(), postDays);
    }
}
