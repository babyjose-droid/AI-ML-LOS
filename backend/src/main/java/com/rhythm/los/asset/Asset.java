package com.rhythm.los.asset;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** The asset financed on one application: what it is, who sells it, quotation, invoice, down payment, insurance and lien. */
@Entity
@Table(name = "asset")
public class Asset {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "application_id") public Long applicationId;
    @Column(name = "asset_no") public String assetNo;
    @Column(name = "asset_class") public String assetClass;
    @Column(name = "dealer_id") public Long dealerId;
    public String make;
    public String model;
    public String variant;
    @Column(name = "manufacture_year") public Integer manufactureYear;
    public int units = 1;
    @Column(name = "serial_no") public String serialNo;
    @Column(name = "engine_no") public String engineNo;
    @Column(name = "registration_no") public String registrationNo;
    @Column(name = "quote_no") public String quoteNo;
    @Column(name = "quote_date") public LocalDate quoteDate;
    @Column(name = "quote_amount") public BigDecimal quoteAmount;
    @Column(name = "invoice_no") public String invoiceNo;
    @Column(name = "invoice_date") public LocalDate invoiceDate;
    @Column(name = "base_price") public BigDecimal basePrice;
    @Column(name = "gst_amount") public BigDecimal gstAmount;
    @Column(name = "other_charges") public BigDecimal otherCharges;
    @Column(name = "invoice_total") public BigDecimal invoiceTotal;
    @Column(name = "invoice_gstin") public String invoiceGstin;
    @Column(name = "down_payment") public BigDecimal downPayment;
    @Column(name = "down_payment_ref") public String downPaymentRef;
    @Column(name = "down_payment_verified_by") public String downPaymentVerifiedBy;
    @Column(name = "down_payment_verified_at") public Instant downPaymentVerifiedAt;
    public String insurer;
    @Column(name = "insurance_policy_no") public String insurancePolicyNo;
    @Column(name = "insured_value") public BigDecimal insuredValue;
    @Column(name = "insurance_expiry") public LocalDate insuranceExpiry;
    @Column(name = "insurance_hypothecated") public boolean insuranceHypothecated;
    @Column(name = "subsidy_amount") public BigDecimal subsidyAmount;
    public String status = "PENDING";
    @Column(name = "evaluation_json") public String evaluationJson;
    @Column(name = "rulings_json") public String rulingsJson;
    @Column(name = "max_loan") public BigDecimal maxLoan;
    @Column(name = "lien_state") public String lienState = "NONE";
    @Column(name = "post_docs_json") public String postDocsJson;
    @Column(name = "disbursed_to") public String disbursedTo;
    @Column(name = "created_by") public String createdBy;
    @Column(name = "created_at") public Instant createdAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
