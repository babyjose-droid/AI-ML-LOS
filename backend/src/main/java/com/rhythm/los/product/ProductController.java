package com.rhythm.los.product;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
public class ProductController {
    private static final Set<String> SEGMENTS = Set.of("MSME", "SALARIED", "MICROFINANCE");
    private static final Set<String> FIELD_RULES = Set.of("ALWAYS", "NEVER", "ABOVE_AMOUNT");
    private static final Set<String> DOC_TYPES = com.rhythm.los.document.DocumentService.TYPES;

    private static final Set<String> STATUSES = Set.of("DRAFT", "LIVE", "PAUSED", "RETIRED");
    private static final Set<String> FREQUENCIES = Set.of("MONTHLY", "FORTNIGHTLY", "WEEKLY", "BULLET");
    /** AUTO = the bureau model when there is a bureau record, else the new-to-credit model (FR-UW-02). */
    private static final Set<String> MODELS = Set.of("AUTO");

    private final ProductRepository repo;
    private final ProductService service;

    public ProductController(ProductRepository repo, ProductService service) {
        this.repo = repo;
        this.service = service;
    }

    public record ProductRequest(
            @NotBlank @Pattern(regexp = "[A-Z0-9-]{2,20}") String code,
            @NotBlank String name,
            @NotBlank String segment,
            boolean secured,
            @NotBlank String status,
            @NotNull @Positive BigDecimal minAmount,
            @NotNull @Positive BigDecimal maxAmount,
            @Min(1) int minTenure,
            @Min(1) int maxTenure,
            @Min(18) int minAge,
            @Max(80) int maxAge,
            @NotNull @DecimalMin("0") BigDecimal processingFeePct,
            @NotNull BigDecimal rateMin,
            @NotNull BigDecimal rateMax,
            @NotBlank String fieldVisitRule,
            BigDecimal fieldVisitThreshold,
            @NotBlank String requiredDocs,
            String videoKycRule,
            BigDecimal videoKycThreshold,
            String bankDataSource,
            BigDecimal autoSanctionLimit,
            BigDecimal minVintageYears,
            BigDecimal minMonthlyIncome,
            String repaymentFrequency,
            @Min(0) @Max(24) Integer moratoriumMonths,
            BigDecimal minProcessingFee,
            BigDecimal foreclosurePct,
            BigDecimal bounceCharge,
            BigDecimal penalRatePct,
            String optionalDocs,
            String scoringModel) {}

    private static final Set<String> VKYC_RULES = Set.of("ON_KYC_REVIEW", "ALWAYS", "ABOVE_AMOUNT");
    private static final Set<String> BANK_SOURCES = Set.of("AA_OR_STATEMENT", "STATEMENT_ONLY", "AA_ONLY");

    @GetMapping("/api/products")
    public List<Product> list() { return repo.findAll(); }

    /** Document types a product can ask for (the document-type master until Master lookups replace it). */
    @GetMapping("/api/products/document-types")
    public List<String> documentTypes() { return DOC_TYPES.stream().sorted().toList(); }

    @GetMapping("/api/products/{code}")
    public Product get(@PathVariable String code) {
        return repo.findByCode(code).orElseThrow(() -> ApiException.notFound("Product " + code));
    }

    /** New product: saved as draft version 1; it takes applications only after the CRO approves it. */
    @PostMapping("/api/admin/products")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@Valid @RequestBody ProductRequest r) {
        Product p = new Product();
        p.setCode(r.code());
        apply(p, r);
        return versionView(r.code(), service.create(p, CurrentUser.get()));
    }

    /** Edit: saved as a draft of the next version; the live product is unchanged until approved. */
    @PutMapping("/api/admin/products/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> update(@PathVariable String code, @Valid @RequestBody ProductRequest r) {
        Product base = get(code);
        Product p = new Product();
        p.setCode(code);
        ProductService.copy(base, p);
        apply(p, r);
        return versionView(code, service.saveDraft(code, p, CurrentUser.get()));
    }

    public record Notes(String notes) {}

    @PostMapping("/api/admin/products/{code}/submit")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> submit(@PathVariable String code, @RequestBody Notes r) {
        return versionView(code, service.submit(code, r.notes(), CurrentUser.get()));
    }

    @DeleteMapping("/api/admin/products/{code}/draft")
    @PreAuthorize("hasRole('ADMIN')")
    public void discard(@PathVariable String code) { service.discard(code, CurrentUser.get()); }

    @PostMapping("/api/products/{code}/decision")
    @PreAuthorize("hasRole('CRO')")
    public Map<String, Object> decide(@PathVariable String code, @RequestBody ProductService.Decision d) {
        return versionView(code, service.decide(code, d, CurrentUser.get()));
    }

    /** Version history with the open draft and its changes against the live product. */
    @GetMapping("/api/products/{code}/versions")
    public List<Map<String, Object>> versions(@PathVariable String code) {
        get(code);
        return service.versions(code).stream().map(v -> versionView(code, v)).toList();
    }

    private Map<String, Object> versionView(String code, ProductVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", v.getId());
        m.put("productCode", code);
        m.put("version", v.getVersion());
        m.put("status", v.getStatus());
        m.put("notes", v.getNotes());
        m.put("createdBy", v.getCreatedBy());
        m.put("createdAt", v.getCreatedAt());
        m.put("approvedBy", v.getApprovedBy());
        m.put("approvedAt", v.getApprovedAt());
        m.put("decisionNote", v.getDecisionNote());
        m.put("definition", com.rhythm.los.common.Json.read(v.getDefinitionJson(), Map.class));
        if (Set.of("DRAFT", "PENDING_APPROVAL").contains(v.getStatus())) m.put("changes", service.changes(code, v));
        return m;
    }

    private void apply(Product p, ProductRequest r) {
        if (!SEGMENTS.contains(r.segment())) throw ApiException.unprocessable("BAD_SEGMENT", "Segment must be one of " + SEGMENTS);
        if (!FIELD_RULES.contains(r.fieldVisitRule())) throw ApiException.unprocessable("BAD_FIELD_RULE", "Field visit rule must be one of " + FIELD_RULES);
        if (r.minAmount().compareTo(r.maxAmount()) > 0) throw ApiException.unprocessable("BAD_RANGE", "Minimum amount is above maximum");
        if (r.minTenure() > r.maxTenure()) throw ApiException.unprocessable("BAD_RANGE", "Minimum tenure is above maximum");
        if (r.rateMin().compareTo(r.rateMax()) > 0) throw ApiException.unprocessable("BAD_RANGE", "Minimum rate is above maximum");
        if ("ABOVE_AMOUNT".equals(r.fieldVisitRule()) && r.fieldVisitThreshold() == null)
            throw ApiException.unprocessable("MISSING_THRESHOLD", "Field visit threshold is needed for ABOVE_AMOUNT");
        for (String d : r.requiredDocs().split("[,|]"))
            if (!DOC_TYPES.contains(d.trim())) throw ApiException.unprocessable("BAD_DOC_TYPE", "Unknown document type " + d.trim());
        p.setName(r.name());
        p.setSegment(r.segment());
        p.setSecured(r.secured());
        if (!STATUSES.contains(r.status())) throw ApiException.unprocessable("BAD_STATUS", "Status must be one of " + STATUSES);
        p.setStatus(r.status());
        p.setMinAmount(r.minAmount());
        p.setMaxAmount(r.maxAmount());
        p.setMinTenure(r.minTenure());
        p.setMaxTenure(r.maxTenure());
        p.setMinAge(r.minAge());
        p.setMaxAge(r.maxAge());
        p.setProcessingFeePct(r.processingFeePct());
        p.setRateMin(r.rateMin());
        p.setRateMax(r.rateMax());
        p.setFieldVisitRule(r.fieldVisitRule());
        p.setFieldVisitThreshold(r.fieldVisitThreshold());
        String vk = r.videoKycRule() == null ? p.getVideoKycRule() : r.videoKycRule();
        String bank = r.bankDataSource() == null ? p.getBankDataSource() : r.bankDataSource();
        if (!VKYC_RULES.contains(vk)) throw ApiException.unprocessable("BAD_VKYC_RULE", "Video KYC rule must be one of " + VKYC_RULES);
        if ("ABOVE_AMOUNT".equals(vk) && r.videoKycThreshold() == null)
            throw ApiException.unprocessable("MISSING_THRESHOLD", "Video KYC threshold is needed for ABOVE_AMOUNT");
        if (!BANK_SOURCES.contains(bank)) throw ApiException.unprocessable("BAD_BANK_SOURCE", "Bank data source must be one of " + BANK_SOURCES);
        if (r.autoSanctionLimit() != null && (r.autoSanctionLimit().signum() <= 0 || r.autoSanctionLimit().compareTo(r.maxAmount()) > 0))
            throw ApiException.unprocessable("BAD_AUTO_SANCTION", "Auto-sanction limit must be positive and not above the product maximum");
        String docs = r.requiredDocs().replace(" ", "");
        if ("STATEMENT_ONLY".equals(bank) && !java.util.Arrays.asList(docs.split("[,|]")).contains("BANK_STATEMENT")) docs = docs + ",BANK_STATEMENT";
        p.setRequiredDocs(docs);
        p.setVideoKycRule(vk);
        p.setVideoKycThreshold(r.videoKycThreshold());
        p.setBankDataSource(bank);
        p.setAutoSanctionLimit(r.autoSanctionLimit());
        String freq = r.repaymentFrequency() == null ? p.getRepaymentFrequency() : r.repaymentFrequency();
        if (freq == null) freq = "MONTHLY";
        if (!FREQUENCIES.contains(freq)) throw ApiException.unprocessable("BAD_FREQUENCY", "Repayment frequency must be one of " + FREQUENCIES);
        String model = r.scoringModel() == null ? p.getScoringModel() : r.scoringModel();
        if (model == null) model = "AUTO";
        if (!MODELS.contains(model)) throw ApiException.unprocessable("BAD_MODEL", "Scoring model must be one of " + MODELS);
        for (BigDecimal x : new BigDecimal[]{r.minVintageYears(), r.minMonthlyIncome(), r.minProcessingFee(), r.foreclosurePct(), r.bounceCharge(), r.penalRatePct()})
            if (x != null && x.signum() < 0) throw ApiException.unprocessable("NEGATIVE", "Limits and charges cannot be negative");
        if (r.penalRatePct() != null && r.penalRatePct().compareTo(BigDecimal.valueOf(36)) > 0)
            throw ApiException.unprocessable("BAD_PENAL", "Penal charge above 36% p.a. is not allowed");
        String optional = r.optionalDocs() == null ? p.getOptionalDocs() : r.optionalDocs().replace(" ", "");
        if (optional != null && !optional.isBlank())
            for (String d : optional.split(","))
                if (!DOC_TYPES.contains(d.trim())) throw ApiException.unprocessable("BAD_DOC_TYPE", "Unknown document type " + d.trim());
        p.setMinVintageYears(r.minVintageYears());
        p.setMinMonthlyIncome(r.minMonthlyIncome());
        p.setRepaymentFrequency(freq);
        p.setMoratoriumMonths(r.moratoriumMonths() == null ? 0 : r.moratoriumMonths());
        p.setMinProcessingFee(r.minProcessingFee());
        p.setForeclosurePct(r.foreclosurePct());
        p.setBounceCharge(r.bounceCharge());
        p.setPenalRatePct(r.penalRatePct());
        p.setOptionalDocs(optional == null || optional.isBlank() ? null : optional);
        p.setScoringModel(model);
        p.setUpdatedAt(Instant.now());
    }
}
