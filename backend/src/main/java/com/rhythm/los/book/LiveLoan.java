package com.rhythm.los.book;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "live_loan")
public class LiveLoan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "loan_ref") private String loanRef;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "app_no") private String appNo;
    @Column(name = "borrower_name") private String borrowerName;
    private String segment;
    @Column(name = "product_code") private String productCode;
    @Column(name = "branch_id") private Long branchId;
    @Column(name = "sanctioned_amount") private BigDecimal sanctionedAmount;
    @Column(name = "disbursed_on") private LocalDate disbursedOn;
    @Column(name = "tenure_months") private int tenureMonths;
    private BigDecimal rate;
    private BigDecimal emi;
    private boolean secured;
    private String source;
    private String status = "ACTIVE";
    @Column(name = "app_pd") private BigDecimal appPd;
    private boolean restructured;
    @Column(name = "restructured_on") private LocalDate restructuredOn;
    @Column(name = "last_feed_date") private LocalDate lastFeedDate;
    private BigDecimal outstanding;
    private BigDecimal overdue = BigDecimal.ZERO;
    private int dpd;
    @Column(name = "max_dpd") private int maxDpd;
    @Column(name = "bounces_3m") private int bounces3m;
    @Column(name = "paid_emis") private int paidEmis;
    @Column(name = "last_payment_date") private LocalDate lastPaymentDate;
    private String classification = "STANDARD";
    @Column(name = "npa_since") private LocalDate npaSince;
    @Column(name = "ecl_stage") private int eclStage = 1;
    private BigDecimal ecl = BigDecimal.ZERO;
    @Column(name = "provision_iracp") private BigDecimal provisionIracp = BigDecimal.ZERO;
    @Column(name = "behaviour_pd") private BigDecimal behaviourPd;
    @Column(name = "ews_score") private int ewsScore;
    @JsonIgnore @Column(name = "ews_reasons_json") private String ewsReasonsJson;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public String getLoanRef() { return loanRef; }
    public void setLoanRef(String v) { this.loanRef = v; }
    public Long getApplicationId() { return applicationId; }
    public void setApplicationId(Long v) { this.applicationId = v; }
    public String getAppNo() { return appNo; }
    public void setAppNo(String v) { this.appNo = v; }
    public String getBorrowerName() { return borrowerName; }
    public void setBorrowerName(String v) { this.borrowerName = v; }
    public String getSegment() { return segment; }
    public void setSegment(String v) { this.segment = v; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String v) { this.productCode = v; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long v) { this.branchId = v; }
    public BigDecimal getSanctionedAmount() { return sanctionedAmount; }
    public void setSanctionedAmount(BigDecimal v) { this.sanctionedAmount = v; }
    public LocalDate getDisbursedOn() { return disbursedOn; }
    public void setDisbursedOn(LocalDate v) { this.disbursedOn = v; }
    public int getTenureMonths() { return tenureMonths; }
    public void setTenureMonths(int v) { this.tenureMonths = v; }
    public BigDecimal getRate() { return rate; }
    public void setRate(BigDecimal v) { this.rate = v; }
    public BigDecimal getEmi() { return emi; }
    public void setEmi(BigDecimal v) { this.emi = v; }
    public boolean isSecured() { return secured; }
    public void setSecured(boolean v) { this.secured = v; }
    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public BigDecimal getAppPd() { return appPd; }
    public void setAppPd(BigDecimal v) { this.appPd = v; }
    public boolean isRestructured() { return restructured; }
    public void setRestructured(boolean v) { this.restructured = v; }
    public LocalDate getRestructuredOn() { return restructuredOn; }
    public void setRestructuredOn(LocalDate v) { this.restructuredOn = v; }
    public LocalDate getLastFeedDate() { return lastFeedDate; }
    public void setLastFeedDate(LocalDate v) { this.lastFeedDate = v; }
    public BigDecimal getOutstanding() { return outstanding; }
    public void setOutstanding(BigDecimal v) { this.outstanding = v; }
    public BigDecimal getOverdue() { return overdue; }
    public void setOverdue(BigDecimal v) { this.overdue = v; }
    public int getDpd() { return dpd; }
    public void setDpd(int v) { this.dpd = v; }
    public int getMaxDpd() { return maxDpd; }
    public void setMaxDpd(int v) { this.maxDpd = v; }
    public int getBounces3m() { return bounces3m; }
    public void setBounces3m(int v) { this.bounces3m = v; }
    public int getPaidEmis() { return paidEmis; }
    public void setPaidEmis(int v) { this.paidEmis = v; }
    public LocalDate getLastPaymentDate() { return lastPaymentDate; }
    public void setLastPaymentDate(LocalDate v) { this.lastPaymentDate = v; }
    public String getClassification() { return classification; }
    public void setClassification(String v) { this.classification = v; }
    public LocalDate getNpaSince() { return npaSince; }
    public void setNpaSince(LocalDate v) { this.npaSince = v; }
    public int getEclStage() { return eclStage; }
    public void setEclStage(int v) { this.eclStage = v; }
    public BigDecimal getEcl() { return ecl; }
    public void setEcl(BigDecimal v) { this.ecl = v; }
    public BigDecimal getProvisionIracp() { return provisionIracp; }
    public void setProvisionIracp(BigDecimal v) { this.provisionIracp = v; }
    public BigDecimal getBehaviourPd() { return behaviourPd; }
    public void setBehaviourPd(BigDecimal v) { this.behaviourPd = v; }
    public int getEwsScore() { return ewsScore; }
    public void setEwsScore(int v) { this.ewsScore = v; }
    public String getEwsReasonsJson() { return ewsReasonsJson; }
    public void setEwsReasonsJson(String v) { this.ewsReasonsJson = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    /** Months since disbursement. */
    public int monthsOnBook(LocalDate on) {
        return (int) Math.max(0, java.time.temporal.ChronoUnit.MONTHS.between(disbursedOn.withDayOfMonth(1), on.withDayOfMonth(1)));
    }
}
