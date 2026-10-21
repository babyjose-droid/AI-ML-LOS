package com.rhythm.los.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

@Entity
@Table(name = "product")
public class Product {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String segment;
    private boolean secured;
    private String status = "LIVE";
    private int version = 1;
    @Column(name = "min_amount") private BigDecimal minAmount;
    @Column(name = "max_amount") private BigDecimal maxAmount;
    @Column(name = "min_tenure") private int minTenure;
    @Column(name = "max_tenure") private int maxTenure;
    @Column(name = "min_age") private int minAge;
    @Column(name = "max_age") private int maxAge;
    @Column(name = "processing_fee_pct") private BigDecimal processingFeePct;
    @Column(name = "rate_min") private BigDecimal rateMin;
    @Column(name = "rate_max") private BigDecimal rateMax;
    @Column(name = "field_visit_rule") private String fieldVisitRule;
    @Column(name = "field_visit_threshold") private BigDecimal fieldVisitThreshold;
    @Column(name = "required_docs") private String requiredDocs;
    @Column(name = "video_kyc_rule") private String videoKycRule = "ON_KYC_REVIEW";
    @Column(name = "video_kyc_threshold") private BigDecimal videoKycThreshold;
    @Column(name = "bank_data_source") private String bankDataSource = "AA_OR_STATEMENT";
    @Column(name = "auto_sanction_limit") private BigDecimal autoSanctionLimit;
    @Column(name = "min_vintage_years") private BigDecimal minVintageYears;
    @Column(name = "min_monthly_income") private BigDecimal minMonthlyIncome;
    @Column(name = "repayment_frequency") private String repaymentFrequency = "MONTHLY";
    @Column(name = "moratorium_months") private int moratoriumMonths;
    @Column(name = "min_processing_fee") private BigDecimal minProcessingFee;
    @Column(name = "foreclosure_pct") private BigDecimal foreclosurePct;
    @Column(name = "bounce_charge") private BigDecimal bounceCharge;
    @Column(name = "penal_rate_pct") private BigDecimal penalRatePct;
    /** Optional documents (asked for, never blocking); required_docs holds the mandatory ones. */
    @Column(name = "optional_docs") private String optionalDocs;
    /** AUTO (bureau model if a bureau record, else new-to-credit), THICK, NTC, RULES (policy rules only, no PD model). */
    @Column(name = "scoring_model") private String scoringModel = "AUTO";
    @Column(name = "collateral_types") private String collateralTypes;
    @Column(name = "asset_classes") private String assetClasses;
    @Column(name = "trade_kind") private String tradeKind;
    @Column(name = "collateral_required") private boolean collateralRequired;
    /** Co-applicant and guarantor rules (JSON, see PartyRules); null on products saved before they existed. */
    @Column(name = "party_rules") private String partyRules;
    /** Video KYC settings (JSON, see VkycPolicy); null on products saved before they existed. */
    @Column(name = "vkyc_policy") private String vkycPolicy;
    /** When party rules were introduced; applications created before keep the old behaviour. */
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(name = "party_rules_since") private Instant partyRulesSince;
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public List<String> requiredDocList() {
        return Arrays.stream(requiredDocs.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public boolean fieldVisitNeeded(double amount) {
        return switch (fieldVisitRule) {
            case "ALWAYS" -> true;
            case "ABOVE_AMOUNT" -> fieldVisitThreshold != null && amount > fieldVisitThreshold.doubleValue();
            default -> false;
        };
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSegment() { return segment; }
    public void setSegment(String segment) { this.segment = segment; }
    public boolean isSecured() { return secured; }
    public void setSecured(boolean secured) { this.secured = secured; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(BigDecimal v) { this.minAmount = v; }
    public BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(BigDecimal v) { this.maxAmount = v; }
    public int getMinTenure() { return minTenure; }
    public void setMinTenure(int v) { this.minTenure = v; }
    public int getMaxTenure() { return maxTenure; }
    public void setMaxTenure(int v) { this.maxTenure = v; }
    public int getMinAge() { return minAge; }
    public void setMinAge(int v) { this.minAge = v; }
    public int getMaxAge() { return maxAge; }
    public void setMaxAge(int v) { this.maxAge = v; }
    public BigDecimal getProcessingFeePct() { return processingFeePct; }
    public void setProcessingFeePct(BigDecimal v) { this.processingFeePct = v; }
    public BigDecimal getRateMin() { return rateMin; }
    public void setRateMin(BigDecimal v) { this.rateMin = v; }
    public BigDecimal getRateMax() { return rateMax; }
    public void setRateMax(BigDecimal v) { this.rateMax = v; }
    public String getFieldVisitRule() { return fieldVisitRule; }
    public void setFieldVisitRule(String v) { this.fieldVisitRule = v; }
    public BigDecimal getFieldVisitThreshold() { return fieldVisitThreshold; }
    public void setFieldVisitThreshold(BigDecimal v) { this.fieldVisitThreshold = v; }
    public String getRequiredDocs() { return requiredDocs; }
    public void setRequiredDocs(String v) { this.requiredDocs = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getVideoKycRule() { return videoKycRule; }
    public void setVideoKycRule(String v) { this.videoKycRule = v; }
    public BigDecimal getVideoKycThreshold() { return videoKycThreshold; }
    public void setVideoKycThreshold(BigDecimal v) { this.videoKycThreshold = v; }
    public String getBankDataSource() { return bankDataSource; }
    public void setBankDataSource(String v) { this.bankDataSource = v; }
    public BigDecimal getAutoSanctionLimit() { return autoSanctionLimit; }
    public void setAutoSanctionLimit(BigDecimal v) { this.autoSanctionLimit = v; }

    /** True when this product needs an approved video KYC before the decision, whatever the document KYC result. */
    public boolean videoKycRequired(double amount) {
        return "ALWAYS".equals(videoKycRule)
                || "ABOVE_AMOUNT".equals(videoKycRule) && videoKycThreshold != null && amount > videoKycThreshold.doubleValue();
    }
    public BigDecimal getMinVintageYears() { return minVintageYears; }
    public void setMinVintageYears(BigDecimal v) { this.minVintageYears = v; }
    public BigDecimal getMinMonthlyIncome() { return minMonthlyIncome; }
    public void setMinMonthlyIncome(BigDecimal v) { this.minMonthlyIncome = v; }
    public String getRepaymentFrequency() { return repaymentFrequency; }
    public void setRepaymentFrequency(String v) { this.repaymentFrequency = v; }
    public int getMoratoriumMonths() { return moratoriumMonths; }
    public void setMoratoriumMonths(int v) { this.moratoriumMonths = v; }
    public BigDecimal getMinProcessingFee() { return minProcessingFee; }
    public void setMinProcessingFee(BigDecimal v) { this.minProcessingFee = v; }
    public BigDecimal getForeclosurePct() { return foreclosurePct; }
    public void setForeclosurePct(BigDecimal v) { this.foreclosurePct = v; }
    public BigDecimal getBounceCharge() { return bounceCharge; }
    public void setBounceCharge(BigDecimal v) { this.bounceCharge = v; }
    public BigDecimal getPenalRatePct() { return penalRatePct; }
    public void setPenalRatePct(BigDecimal v) { this.penalRatePct = v; }
    public String getOptionalDocs() { return optionalDocs; }
    public void setOptionalDocs(String v) { this.optionalDocs = v; }
    public String getScoringModel() { return scoringModel; }
    public void setScoringModel(String v) { this.scoringModel = v; }

    /** Fees and charges for the KFS. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public com.rhythm.los.sanction.KfsCalculator.Charges charges() {
        return new com.rhythm.los.sanction.KfsCalculator.Charges(processingFeePct == null ? 0 : processingFeePct.doubleValue(),
                minProcessingFee == null ? 0 : minProcessingFee.doubleValue(), foreclosurePct == null ? 0 : foreclosurePct.doubleValue(),
                bounceCharge == null ? 0 : bounceCharge.doubleValue(), penalRatePct == null ? 0 : penalRatePct.doubleValue(), repaymentFrequency);
    }
    public String getCollateralTypes() { return collateralTypes; }
    public String getTradeKind() { return tradeKind; }
    public void setTradeKind(String v) { this.tradeKind = v; }
    public String getAssetClasses() { return assetClasses; }
    public void setAssetClasses(String v) { this.assetClasses = v; }
    /** Asset finance: the asset classes this product finances (CV, CAR, EQUIPMENT, MEDICAL, SOLAR, DURABLE); empty for other products. */
    public java.util.List<String> assetClassList() {
        return assetClasses == null || assetClasses.isBlank() ? java.util.List.of() : java.util.Arrays.stream(assetClasses.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
    public void setCollateralTypes(String v) { this.collateralTypes = v; }
    public boolean isCollateralRequired() { return collateralRequired; }
    public void setCollateralRequired(boolean v) { this.collateralRequired = v; }
    public java.util.List<String> collateralTypeList() {
        return collateralTypes == null || collateralTypes.isBlank() ? java.util.List.of() : java.util.Arrays.stream(collateralTypes.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
    public String getPartyRules() { return partyRules; }
    public void setPartyRules(String v) { this.partyRules = v; }
    @com.fasterxml.jackson.annotation.JsonIgnore public Instant getPartyRulesSince() { return partyRulesSince; }
    public void setPartyRulesSince(Instant v) { this.partyRulesSince = v; }
    public String getVkycPolicy() { return vkycPolicy; }
    public void setVkycPolicy(String v) { this.vkycPolicy = v; }
}
