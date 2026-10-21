package com.rhythm.los.trade;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** An invoice offered for financing under a limit: one drawdown. */
@Entity
@Table(name = "trade_invoice")
public class TradeInvoice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "ref_no") public String refNo;
    @Column(name = "limit_id") public Long limitId;
    @Column(name = "counterparty_id") public Long counterpartyId;
    @Column(name = "invoice_no") public String invoiceNo;
    @Column(name = "invoice_date") public LocalDate invoiceDate;
    @Column(name = "due_date") public LocalDate dueDate;
    @Column(name = "po_no") public String poNo;
    @Column(name = "seller_gstin") public String sellerGstin;
    @Column(name = "buyer_gstin") public String buyerGstin;
    @Column(name = "base_amount") public BigDecimal baseAmount;
    @Column(name = "gst_amount") public BigDecimal gstAmount;
    public BigDecimal total;
    @Column(name = "advance_amount") public BigDecimal advanceAmount;
    @Column(name = "file_name") public String fileName;
    @Column(name = "storage_path") @com.fasterxml.jackson.annotation.JsonIgnore public String storagePath;
    @Column(name = "file_hash") public String fileHash;
    @Column(name = "fields_json") public String fieldsJson;
    @Column(name = "read_confidence") public BigDecimal readConfidence;
    public String engine;
    public String status = "SUBMITTED";
    @Column(name = "checks_json") public String checksJson;
    @Column(name = "rulings_json") public String rulingsJson;
    @Column(name = "confirmation_ref") public String confirmationRef;
    @Column(name = "confirmed_by") public String confirmedBy;
    @Column(name = "confirmed_at") public Instant confirmedAt;
    @Column(name = "funded_amount") public BigDecimal fundedAmount;
    @Column(name = "interest_amount") public BigDecimal interestAmount;
    @Column(name = "funded_by") public String fundedBy;
    @Column(name = "funded_at") public Instant fundedAt;
    public String utr;
    @Column(name = "paid_to") public String paidTo;
    @Column(name = "repaid_amount") public BigDecimal repaidAmount = BigDecimal.ZERO;
    @Column(name = "repaid_at") public Instant repaidAt;
    @Column(name = "repayment_ref") public String repaymentRef;
    @Column(name = "rejected_reason") public String rejectedReason;
    @Column(name = "submitted_by") public String submittedBy;
    @Column(name = "submitted_at") public Instant submittedAt = Instant.now();
    @Column(name = "updated_at") public Instant updatedAt = Instant.now();
}
