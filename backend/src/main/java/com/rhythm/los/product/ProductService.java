package com.rhythm.los.product;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Product builder with versions: an admin edits a draft and sends it for approval; the CRO (never the maker) publishes it.
 * The product table always holds the published definition. Applications keep the version they were created on, so a
 * change of fees, limits or documents never alters an application already in progress.
 */
@Service
public class ProductService {
    private final ProductRepository products;
    private final ProductVersionRepository versions;
    private final AuditService audit;
    private final Map<String, Product> cache = new ConcurrentHashMap<>();

    public ProductService(ProductRepository products, ProductVersionRepository versions, AuditService audit) {
        this.products = products;
        this.versions = versions;
        this.audit = audit;
    }

    public Product live(String code) {
        return products.findByCode(code).orElseThrow(() -> ApiException.notFound("Product " + code));
    }

    /** The product as it was in the given version (the live product when the version is unknown). */
    public Product definition(String code, Integer version) {
        if (version == null) return live(code);
        String key = com.rhythm.los.tenant.TenantContext.code() + ":" + code + "#" + version;
        Product hit = cache.get(key);
        if (hit != null) return hit;
        Optional<ProductVersion> v = versions.findByProductCodeAndVersion(code, version);
        if (v.isEmpty() || !Set.of("PUBLISHED", "SUPERSEDED").contains(v.get().getStatus())) return live(code);
        Product p = Json.read(v.get().getDefinitionJson(), Product.class);
        cache.put(key, p);
        return p;
    }

    public List<ProductVersion> versions(String code) { return versions.findByProductCodeOrderByVersionDesc(code); }

    public Optional<ProductVersion> openDraft(String code) {
        return versions.findByProductCodeOrderByVersionDesc(code).stream()
                .filter(v -> Set.of("DRAFT", "PENDING_APPROVAL").contains(v.getStatus())).findFirst();
    }

    /** A new product starts as a draft; it can take applications only once approved. */
    @Transactional
    public ProductVersion create(Product draft, CurrentUser by) {
        if (products.existsByCode(draft.getCode())) throw ApiException.conflict("DUPLICATE_CODE", "Product code already exists");
        Product p = new Product();
        p.setCode(draft.getCode());
        copy(draft, p);
        p.setStatus("DRAFT");
        p.setVersion(0);
        products.save(p);
        ProductVersion v = newVersion(draft.getCode(), 1, draft, by);
        audit.record(by, "product.draft_created", "PRODUCT", draft.getCode(), null, draft.getName() + " v1 (draft)");
        return v;
    }

    /** Saves the edit as a draft of the next version; the live product is unchanged until the draft is approved. */
    @Transactional
    public ProductVersion saveDraft(String code, Product edited, CurrentUser by) {
        Product live = live(code);
        Optional<ProductVersion> open = openDraft(code);
        if (open.isPresent() && "PENDING_APPROVAL".equals(open.get().getStatus()))
            throw ApiException.conflict("PENDING_APPROVAL", "Version " + open.get().getVersion() + " is waiting for approval; approve or reject it first");
        edited.setCode(code);
        ProductVersion v = open.orElseGet(() -> newVersion(code, live.getVersion() + 1, edited, by));
        Product def = new Product();
        def.setCode(code);
        copy(edited, def);
        def.setVersion(v.getVersion());
        v.setDefinitionJson(Json.write(def));
        versions.save(v);
        audit.record(by, "product.draft_saved", "PRODUCT", code, null, "v" + v.getVersion());
        return v;
    }

    @Transactional
    public ProductVersion submit(String code, String notes, CurrentUser by) {
        ProductVersion v = openDraft(code).filter(x -> "DRAFT".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.conflict("NO_DRAFT", "There is no draft of " + code + " to submit"));
        if (notes == null || notes.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Describe the change in at least 10 characters");
        v.setNotes(notes.trim());
        v.setStatus("PENDING_APPROVAL");
        v.setSubmittedAt(Instant.now());
        versions.save(v);
        audit.record(by, "product.submitted", "PRODUCT", code, null, "v" + v.getVersion() + ": " + notes.trim());
        return v;
    }

    public record Decision(boolean approve, String note) {}

    @Transactional
    public ProductVersion decide(String code, Decision d, CurrentUser by) {
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can approve a product change");
        ProductVersion v = openDraft(code).filter(x -> "PENDING_APPROVAL".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.conflict("NOT_PENDING", "No version of " + code + " is waiting for approval"));
        if (by.username().equals(v.getCreatedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who made a product change cannot approve it");
        String note = d.note() == null ? "" : d.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        v.setApprovedBy(by.username());
        v.setApprovedAt(Instant.now());
        v.setDecisionNote(note);
        if (!d.approve()) {
            v.setStatus("REJECTED");
            versions.save(v);
            audit.record(by, "product.rejected", "PRODUCT", code, null, "v" + v.getVersion() + ": " + note);
            return v;
        }
        for (ProductVersion old : versions.findByProductCodeOrderByVersionDesc(code))
            if ("PUBLISHED".equals(old.getStatus())) { old.setStatus("SUPERSEDED"); versions.save(old); }
        v.setStatus("PUBLISHED");
        versions.save(v);
        Product def = Json.read(v.getDefinitionJson(), Product.class);
        Product p = live(code);
        copy(def, p);
        p.setVersion(v.getVersion());
        p.setUpdatedAt(Instant.now());
        products.save(p);
        audit.record(by, "product.published", "PRODUCT", code, null, "v" + v.getVersion() + " now live: " + note);
        return v;
    }

    @Transactional
    public void discard(String code, CurrentUser by) {
        ProductVersion v = openDraft(code).filter(x -> "DRAFT".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.conflict("NO_DRAFT", "There is no draft of " + code));
        versions.delete(v);
        audit.record(by, "product.draft_deleted", "PRODUCT", code, null, "v" + v.getVersion());
    }

    /** Field differences between the live product and a version, for the approver. */
    public List<String> changes(String code, ProductVersion v) {
        Map<String, Object> a = Json.read(Json.write(live(code)), Map.class);
        Map<String, Object> b = Json.read(v.getDefinitionJson(), Map.class);
        List<String> out = new ArrayList<>();
        for (String k : new TreeSet<>(b.keySet())) {
            if (Set.of("id", "version", "updatedAt").contains(k)) continue;
            Object x = a.get(k), y = b.get(k);
            if (!Objects.equals(norm(x), norm(y))) out.add(k + ": " + (x == null ? "—" : x) + " → " + (y == null ? "—" : y));
        }
        return out;
    }

    private static String norm(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        return o.toString();
    }

    private ProductVersion newVersion(String code, int version, Product def, CurrentUser by) {
        ProductVersion v = new ProductVersion();
        v.setProductCode(code);
        v.setVersion(version);
        v.setStatus("DRAFT");
        v.setCreatedBy(by.username());
        Product d = new Product();
        d.setCode(code);
        copy(def, d);
        d.setVersion(version);
        v.setDefinitionJson(Json.write(d));
        return versions.save(v);
    }

    /** Copies every definition field (not id, code or version). */
    static void copy(Product f, Product t) {
        t.setName(f.getName());
        t.setSegment(f.getSegment());
        t.setSecured(f.isSecured());
        t.setStatus(f.getStatus());
        t.setMinAmount(f.getMinAmount());
        t.setMaxAmount(f.getMaxAmount());
        t.setMinTenure(f.getMinTenure());
        t.setMaxTenure(f.getMaxTenure());
        t.setMinAge(f.getMinAge());
        t.setMaxAge(f.getMaxAge());
        t.setProcessingFeePct(f.getProcessingFeePct());
        t.setRateMin(f.getRateMin());
        t.setRateMax(f.getRateMax());
        t.setFieldVisitRule(f.getFieldVisitRule());
        t.setFieldVisitThreshold(f.getFieldVisitThreshold());
        t.setRequiredDocs(f.getRequiredDocs());
        t.setOptionalDocs(f.getOptionalDocs());
        t.setVideoKycRule(f.getVideoKycRule());
        t.setVideoKycThreshold(f.getVideoKycThreshold());
        t.setBankDataSource(f.getBankDataSource());
        t.setAutoSanctionLimit(f.getAutoSanctionLimit());
        t.setMinVintageYears(f.getMinVintageYears());
        t.setMinMonthlyIncome(f.getMinMonthlyIncome());
        t.setRepaymentFrequency(f.getRepaymentFrequency());
        t.setMoratoriumMonths(f.getMoratoriumMonths());
        t.setMinProcessingFee(f.getMinProcessingFee());
        t.setForeclosurePct(f.getForeclosurePct());
        t.setBounceCharge(f.getBounceCharge());
        t.setPenalRatePct(f.getPenalRatePct());
        t.setScoringModel(f.getScoringModel());
        t.setCollateralTypes(f.getCollateralTypes());
        t.setCollateralRequired(f.isCollateralRequired());
        t.setPartyRules(f.getPartyRules());
        t.setVkycPolicy(f.getVkycPolicy());
        t.setUpdatedAt(Instant.now());
    }
}
