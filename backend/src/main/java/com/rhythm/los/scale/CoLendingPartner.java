package com.rhythm.los.scale;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "colending_partner")
public class CoLendingPartner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String code;
    private String name;
    private String products;
    @Column(name = "max_ticket") private BigDecimal maxTicket;
    @Column(name = "allowed_bands") private String allowedBands;
    @Column(name = "ntc_allowed") private boolean ntcAllowed;
    @Column(name = "min_vintage_years") private BigDecimal minVintageYears;
    @Column(name = "psl_only") private boolean pslOnly;
    @Column(name = "partner_share") private BigDecimal partnerShare;
    @Column(name = "partner_rate") private BigDecimal partnerRate;
    private String status = "ACTIVE";
    @Column(name = "updated_by") private String updatedBy;
    @Column(name = "updated_at") private Instant updatedAt;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String v) { this.code = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getProducts() { return products; }
    public void setProducts(String v) { this.products = v; }
    public BigDecimal getMaxTicket() { return maxTicket; }
    public void setMaxTicket(BigDecimal v) { this.maxTicket = v; }
    public String getAllowedBands() { return allowedBands; }
    public void setAllowedBands(String v) { this.allowedBands = v; }
    public boolean isNtcAllowed() { return ntcAllowed; }
    public void setNtcAllowed(boolean v) { this.ntcAllowed = v; }
    public BigDecimal getMinVintageYears() { return minVintageYears; }
    public void setMinVintageYears(BigDecimal v) { this.minVintageYears = v; }
    public boolean isPslOnly() { return pslOnly; }
    public void setPslOnly(boolean v) { this.pslOnly = v; }
    public BigDecimal getPartnerShare() { return partnerShare; }
    public void setPartnerShare(BigDecimal v) { this.partnerShare = v; }
    public BigDecimal getPartnerRate() { return partnerRate; }
    public void setPartnerRate(BigDecimal v) { this.partnerRate = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
