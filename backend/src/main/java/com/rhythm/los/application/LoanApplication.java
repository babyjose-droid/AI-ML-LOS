package com.rhythm.los.application;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.Map;

@Entity
@Table(name = "loan_application")
public class LoanApplication {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "app_no")
    private String appNo;
    @Column(name = "product_code")
    private String productCode;
    @Column(name = "branch_id")
    private Long branchId;
    @Column(name = "created_by")
    private String createdBy;
    @Column(name = "applicant_name")
    private String applicantName;
    private String pan;
    private String mobile;
    private String email;
    private LocalDate dob;
    private String gender;
    private String address;
    private String city;
    private String pincode;
    private String segment;
    @Column(name = "business_name")
    private String businessName;
    @Column(name = "business_vintage_years")
    private BigDecimal businessVintageYears;
    @Column(name = "declared_monthly_income")
    private BigDecimal declaredMonthlyIncome;
    @Column(name = "essential_expenses")
    private BigDecimal essentialExpenses;
    @Column(name = "loan_amount")
    private BigDecimal loanAmount;
    @Column(name = "tenure_months")
    private int tenureMonths;
    private String purpose;
    @Column(name = "bank_account_no")
    private String bankAccountNo;
    @Column(name = "bank_ifsc")
    private String bankIfsc;
    @Column(name = "consent_bureau")
    private boolean consentBureau;
    @Column(name = "consent_aa")
    private boolean consentAa;
    @Column(name = "consent_kyc")
    private boolean consentKyc;
    @Column(name = "consent_at")
    private Instant consentAt;
    @Column(name = "rejection_reason")
    private String rejectionReason;

    // one state column per state-machine domain; change only through ApplicationService.transition
    @Column(name = "app_state") private String appState;
    @Column(name = "kyc_state") private String kycState;
    @Column(name = "data_state") private String dataState;
    @Column(name = "docs_state") private String docsState;
    @Column(name = "field_state") private String fieldState;
    @Column(name = "decision_state") private String decisionState;
    @Column(name = "fraud_state") private String fraudState;
    @Column(name = "sanction_state") private String sanctionState;
    @Column(name = "disb_state") private String disbState;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();
    @Version private long version;

    public LoanApplication() {
        for (Domain d : Domain.values()) setState(d, StateMachine.initial(d));
    }

    public String state(Domain d) {
        return switch (d) {
            case APP -> appState;
            case KYC -> kycState;
            case DATA -> dataState;
            case DOCS -> docsState;
            case FIELD -> fieldState;
            case DECISION -> decisionState;
            case FRAUD -> fraudState;
            case SANCTION -> sanctionState;
            case DISB -> disbState;
        };
    }

    void setState(Domain d, String s) {
        switch (d) {
            case APP -> appState = s;
            case KYC -> kycState = s;
            case DATA -> dataState = s;
            case DOCS -> docsState = s;
            case FIELD -> fieldState = s;
            case DECISION -> decisionState = s;
            case FRAUD -> fraudState = s;
            case SANCTION -> sanctionState = s;
            case DISB -> disbState = s;
        }
        updatedAt = Instant.now();
    }

    public Map<String, String> states() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Domain d : Domain.values()) m.put(d.name(), state(d));
        return m;
    }

    public int ageYears() { return Period.between(dob, LocalDate.now()).getYears(); }

    public Long getId() { return id; }
    public String getAppNo() { return appNo; }
    public void setAppNo(String v) { this.appNo = v; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String v) { this.productCode = v; }
    public Long getBranchId() { return branchId; }
    public void setBranchId(Long v) { this.branchId = v; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String v) { this.createdBy = v; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String v) { this.applicantName = v; }
    public String getPan() { return pan; }
    public void setPan(String v) { this.pan = v; }
    public String getMobile() { return mobile; }
    public void setMobile(String v) { this.mobile = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate v) { this.dob = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getAddress() { return address; }
    public void setAddress(String v) { this.address = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getPincode() { return pincode; }
    public void setPincode(String v) { this.pincode = v; }
    public String getSegment() { return segment; }
    public void setSegment(String v) { this.segment = v; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String v) { this.businessName = v; }
    public BigDecimal getBusinessVintageYears() { return businessVintageYears; }
    public void setBusinessVintageYears(BigDecimal v) { this.businessVintageYears = v; }
    public BigDecimal getDeclaredMonthlyIncome() { return declaredMonthlyIncome; }
    public void setDeclaredMonthlyIncome(BigDecimal v) { this.declaredMonthlyIncome = v; }
    public BigDecimal getEssentialExpenses() { return essentialExpenses; }
    public void setEssentialExpenses(BigDecimal v) { this.essentialExpenses = v; }
    public BigDecimal getLoanAmount() { return loanAmount; }
    public void setLoanAmount(BigDecimal v) { this.loanAmount = v; }
    public int getTenureMonths() { return tenureMonths; }
    public void setTenureMonths(int v) { this.tenureMonths = v; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String v) { this.purpose = v; }
    public String getBankAccountNo() { return bankAccountNo; }
    public void setBankAccountNo(String v) { this.bankAccountNo = v; }
    public String getBankIfsc() { return bankIfsc; }
    public void setBankIfsc(String v) { this.bankIfsc = v; }
    public boolean isConsentBureau() { return consentBureau; }
    public void setConsentBureau(boolean v) { this.consentBureau = v; }
    public boolean isConsentAa() { return consentAa; }
    public void setConsentAa(boolean v) { this.consentAa = v; }
    public boolean isConsentKyc() { return consentKyc; }
    public void setConsentKyc(boolean v) { this.consentKyc = v; }
    public Instant getConsentAt() { return consentAt; }
    public void setConsentAt(Instant v) { this.consentAt = v; }
    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String v) { this.rejectionReason = v; }
    public String getAppState() { return appState; }
    public String getKycState() { return kycState; }
    public String getDataState() { return dataState; }
    public String getDocsState() { return docsState; }
    public String getFieldState() { return fieldState; }
    public String getDecisionState() { return decisionState; }
    public String getFraudState() { return fraudState; }
    public String getSanctionState() { return sanctionState; }
    public String getDisbState() { return disbState; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
