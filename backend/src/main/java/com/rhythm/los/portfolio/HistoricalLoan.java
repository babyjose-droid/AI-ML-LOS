package com.rhythm.los.portfolio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** A past loan with its 12-month outcome. Used for policy simulation and model training. */
@Entity
@Table(name = "historical_loan")
public class HistoricalLoan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String ref;
    private String source;
    private String segment;
    @Column(name = "product_code") private String productCode;
    private BigDecimal amount;
    @Column(name = "disbursed_on") private LocalDate disbursedOn;
    @JsonIgnore @Column(name = "input_json") private String inputJson;
    @Column(name = "outcome_bad") private boolean outcomeBad;
    @Column(name = "created_at") private Instant createdAt = Instant.now();

    protected HistoricalLoan() {}

    public HistoricalLoan(String ref, String source, String segment, String productCode, BigDecimal amount, LocalDate disbursedOn,
                          String inputJson, boolean outcomeBad) {
        this.ref = ref;
        this.source = source;
        this.segment = segment;
        this.productCode = productCode;
        this.amount = amount;
        this.disbursedOn = disbursedOn;
        this.inputJson = inputJson;
        this.outcomeBad = outcomeBad;
    }

    public Long getId() { return id; }
    public String getRef() { return ref; }
    public String getSource() { return source; }
    public String getSegment() { return segment; }
    public String getProductCode() { return productCode; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getDisbursedOn() { return disbursedOn; }
    public String getInputJson() { return inputJson; }
    public boolean isOutcomeBad() { return outcomeBad; }
}
