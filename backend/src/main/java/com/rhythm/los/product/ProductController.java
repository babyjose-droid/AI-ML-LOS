package com.rhythm.los.product;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

@RestController
public class ProductController {
    private static final Set<String> SEGMENTS = Set.of("MSME", "SALARIED", "MICROFINANCE");
    private static final Set<String> FIELD_RULES = Set.of("ALWAYS", "NEVER", "ABOVE_AMOUNT");
    private static final Set<String> DOC_TYPES = com.rhythm.los.document.DocumentService.TYPES;

    private final ProductRepository repo;
    private final AuditService audit;

    public ProductController(ProductRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
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
            @NotBlank String requiredDocs) {}

    @GetMapping("/api/products")
    public List<Product> list() { return repo.findAll(); }

    @GetMapping("/api/products/{code}")
    public Product get(@PathVariable String code) {
        return repo.findByCode(code).orElseThrow(() -> ApiException.notFound("Product " + code));
    }

    @PostMapping("/api/admin/products")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Product create(@Valid @RequestBody ProductRequest r) {
        if (repo.existsByCode(r.code())) throw ApiException.conflict("DUPLICATE_CODE", "Product code already exists");
        Product p = new Product();
        p.setCode(r.code());
        apply(p, r);
        Product saved = repo.save(p);
        audit.record(CurrentUser.get(), "product.create", "PRODUCT", r.code(), null, r.name());
        return saved;
    }

    @PutMapping("/api/admin/products/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public Product update(@PathVariable String code, @Valid @RequestBody ProductRequest r) {
        Product p = get(code);
        apply(p, r);
        p.setVersion(p.getVersion() + 1);
        audit.record(CurrentUser.get(), "product.update", "PRODUCT", code, null, "Version " + p.getVersion());
        return repo.save(p);
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
        p.setRequiredDocs(r.requiredDocs().replace(" ", ""));
        p.setUpdatedAt(Instant.now());
    }
}
