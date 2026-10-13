package com.rhythm.los.org;

import jakarta.persistence.*;

@Entity
@Table(name = "branch")
public class Branch {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String level;
    @Column(name = "parent_id")
    private Long parentId;
    private String city;
    private boolean active = true;
    /** PIN codes this branch serves (comma-separated); used for geography eligibility and to route portal applications. */
    private String pincodes;
    /** Product codes offered here (comma-separated); empty means every product. */
    private String products;

    public String getPincodes() { return pincodes; }
    public void setPincodes(String v) { this.pincodes = v; }
    public String getProducts() { return products; }
    public void setProducts(String v) { this.products = v; }

    public boolean servesPin(String pin) {
        if (pin == null || pincodes == null || pincodes.isBlank()) return false;
        for (String p : pincodes.split(",")) if (p.trim().equals(pin.trim())) return true;
        return false;
    }

    public boolean offers(String product) {
        if (products == null || products.isBlank()) return true;
        for (String p : products.split(",")) if (p.trim().equalsIgnoreCase(product)) return true;
        return false;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
