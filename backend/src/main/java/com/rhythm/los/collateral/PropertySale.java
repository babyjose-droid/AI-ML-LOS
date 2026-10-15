package com.rhythm.los.collateral;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A registered sale of a property (comparable for the automated valuation). */
@Entity
@Table(name = "property_sale")
public class PropertySale {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private String type;
    private String pincode;
    private String locality;
    private Double lat;
    private Double lng;
    @Column(name = "area_sqft") private BigDecimal areaSqft;
    private BigDecimal price;
    @Column(name = "sale_date") private LocalDate saleDate;
    private String source = "IMPORT";
    @Column(name = "document_no") private String documentNo;
    @Column(name = "added_by") private String addedBy = "system";
    @Column(name = "added_at") private Instant addedAt = Instant.now();

    public Long getId() { return id; }
    public String getType() { return type; }
    public void setType(String v) { this.type = v; }
    public String getPincode() { return pincode; }
    public void setPincode(String v) { this.pincode = v; }
    public String getLocality() { return locality; }
    public void setLocality(String v) { this.locality = v; }
    public Double getLat() { return lat; }
    public void setLat(Double v) { this.lat = v; }
    public Double getLng() { return lng; }
    public void setLng(Double v) { this.lng = v; }
    public BigDecimal getAreaSqft() { return areaSqft; }
    public void setAreaSqft(BigDecimal v) { this.areaSqft = v; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal v) { this.price = v; }
    public LocalDate getSaleDate() { return saleDate; }
    public void setSaleDate(LocalDate v) { this.saleDate = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getDocumentNo() { return documentNo; }
    public void setDocumentNo(String v) { this.documentNo = v; }
    public String getAddedBy() { return addedBy; }
    public void setAddedBy(String v) { this.addedBy = v; }
    public Instant getAddedAt() { return addedAt; }

    ComparableAvm.Sale sale() {
        return new ComparableAvm.Sale(id, type, pincode, locality, lat, lng, areaSqft.doubleValue(), price.doubleValue(), saleDate, source);
    }
}
