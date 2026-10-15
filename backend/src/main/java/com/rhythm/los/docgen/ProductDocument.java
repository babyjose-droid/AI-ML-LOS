package com.rhythm.los.docgen;

import jakarta.persistence.*;
import java.time.Instant;

/** A document a product uses, at which stage, signed by whom. */
@Entity
@Table(name = "product_document")
public class ProductDocument {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "product_code") private String productCode;
    @Column(name = "template_code") private String templateCode;
    private String stage;
    private String signers = "";
    private String condition = "ALWAYS";
    private boolean required = true;
    private int sort;

    public Long getId() { return id; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String v) { this.productCode = v; }
    public String getTemplateCode() { return templateCode; }
    public void setTemplateCode(String v) { this.templateCode = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { this.stage = v; }
    public String getSigners() { return signers; }
    public void setSigners(String v) { this.signers = v; }
    public String getCondition() { return condition; }
    public void setCondition(String v) { this.condition = v; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean v) { this.required = v; }
    public int getSort() { return sort; }
    public void setSort(int v) { this.sort = v; }
}
