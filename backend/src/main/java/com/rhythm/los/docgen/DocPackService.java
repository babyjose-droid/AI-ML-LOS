package com.rhythm.los.docgen;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.StorageService;
import com.rhythm.los.integration.VendorException;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * The documents each product uses (Products → Documents) and each application's document pack: made from the approved
 * template (the version an application started on stays), stored as PDF with a fingerprint, re-made when the details
 * change, signed by OTP by each person who must sign, and (when the product says so) required before disbursement.
 */
@Service
public class DocPackService {
    public static final List<String> STAGES = List.of("APPLICATION", "SANCTION", "PRE_DISBURSE", "POST_DISBURSE", "CLOSURE");
    public static final Set<String> SIGNERS = Set.of("BORROWER", "CO_APPLICANT", "GUARANTOR");
    public static final Set<String> CONDITIONS = Set.of("ALWAYS", "HAS_CO_APPLICANT", "HAS_GUARANTOR", "HAS_COLLATERAL", "UNSECURED");
    static final Set<String> BEFORE_DISBURSEMENT = Set.of("APPLICATION", "SANCTION", "PRE_DISBURSE");
    static final Set<Role> SETUP = EnumSet.of(Role.ADMIN, Role.CRO, Role.COMPLIANCE);
    static final Set<Role> MAKE = EnumSet.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER, Role.CREDIT_MANAGER, Role.CRO);
    static final Set<Role> SIGN = EnumSet.of(Role.ADMIN, Role.SALES, Role.OPERATIONS, Role.CREDIT_OFFICER);

    private final ProductDocumentRepository mappings;
    private final DocTemplateRepository templates;
    private final AppDocumentRepository docs;
    private final AppDocumentSignatureRepository signatures;
    private final DocTemplateService library;
    private final DocData data;
    private final DocRenderer renderer;
    private final StorageService storage;
    private final ApplicationService apps;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    @Value("${rhythm.kfs.otp:123456}") String demoOtp = "123456";

    public DocPackService(ProductDocumentRepository mappings, DocTemplateRepository templates, AppDocumentRepository docs, AppDocumentSignatureRepository signatures,
                          DocTemplateService library, DocData data, DocRenderer renderer, StorageService storage, ApplicationService apps,
                          JdbcTemplate jdbc, AuditService audit) {
        this.mappings = mappings;
        this.templates = templates;
        this.docs = docs;
        this.signatures = signatures;
        this.library = library;
        this.data = data;
        this.renderer = renderer;
        this.storage = storage;
        this.apps = apps;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- product setup

    public boolean gate(String productCode) {
        List<Boolean> g = jdbc.queryForList("select doc_gate from product where code = ?", Boolean.class, productCode);
        return !g.isEmpty() && Boolean.TRUE.equals(g.get(0));
    }

    public Map<String, Object> productDocs(String productCode) {
        library.ensureDefaults();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("product", productCode);
        m.put("gate", gate(productCode));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductDocument d : mappings.findByProductCodeOrderBySortAscIdAsc(productCode)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("templateCode", d.getTemplateCode());
            x.put("stage", d.getStage());
            x.put("signers", splitSigners(d.getSigners()));
            x.put("condition", d.getCondition());
            x.put("required", d.isRequired());
            x.put("name", approvedName(d.getTemplateCode()));
            x.put("languages", templates.findByCodeOrderByVersionDesc(d.getTemplateCode()).stream().filter(t -> "APPROVED".equals(t.getStatus())).map(DocTemplate::getLanguage).distinct().sorted().toList());
            rows.add(x);
        }
        m.put("documents", rows);
        m.put("stages", STAGES);
        m.put("conditions", CONDITIONS);
        m.put("signers", SIGNERS);
        return m;
    }

    String approvedName(String code) {
        return templates.findByCodeOrderByVersionDesc(code).stream().filter(t -> "APPROVED".equals(t.getStatus())).map(DocTemplate::getName).findFirst()
                .orElseGet(() -> templates.findByCodeOrderByVersionDesc(code).stream().map(DocTemplate::getName).findFirst().orElse(code));
    }

    static List<String> splitSigners(String s) { return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList(); }

    public record DocLine(String templateCode, String stage, List<String> signers, String condition, Boolean required) {}
    public record ProductDocsRequest(Boolean gate, List<DocLine> documents, String note) {}

    @Transactional
    public Map<String, Object> setProductDocs(String productCode, ProductDocsRequest r, CurrentUser by) {
        if (!SETUP.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "An admin, compliance or the CRO sets a product's documents");
        library.ensureDefaults();
        if (jdbc.queryForObject("select count(*) from product where code = ?", Integer.class, productCode) == 0) throw ApiException.notFound("Product " + productCode);
        if (r.note() == null || r.note().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Say what changed (at least 10 characters)");
        List<DocLine> lines = r.documents() == null ? List.of() : r.documents();
        Set<String> seen = new HashSet<>();
        List<ProductDocument> next = new ArrayList<>();
        for (DocLine l : lines) {
            if (l.templateCode() == null || !seen.add(l.templateCode())) throw ApiException.unprocessable("DUPLICATE", "Each document once: " + l.templateCode());
            List<DocTemplate> ts = templates.findByCodeOrderByVersionDesc(l.templateCode());
            if (ts.isEmpty()) throw ApiException.unprocessable("UNKNOWN_TEMPLATE", "No template " + l.templateCode());
            if ("CLAUSE".equals(ts.get(0).getDocType())) throw ApiException.unprocessable("CLAUSE", l.templateCode() + " is a clause: include it inside a document");
            if (ts.stream().noneMatch(t -> "APPROVED".equals(t.getStatus()))) throw ApiException.unprocessable("NOT_APPROVED", l.templateCode() + " has no approved version yet");
            if (!STAGES.contains(l.stage())) throw ApiException.unprocessable("BAD_STAGE", "Stage must be one of " + STAGES);
            List<String> signers = l.signers() == null ? List.of() : l.signers();
            if (!SIGNERS.containsAll(signers)) throw ApiException.unprocessable("BAD_SIGNER", "Signers are " + SIGNERS);
            if (Set.of("POST_DISBURSE", "CLOSURE").contains(l.stage()) && !signers.isEmpty()) throw ApiException.unprocessable("BAD_SIGNER", "Letters after disbursement are not signed by the borrower");
            String cond = l.condition() == null ? "ALWAYS" : l.condition();
            if (!CONDITIONS.contains(cond)) throw ApiException.unprocessable("BAD_CONDITION", "Condition must be one of " + CONDITIONS);
            ProductDocument d = new ProductDocument();
            d.setProductCode(productCode);
            d.setTemplateCode(l.templateCode());
            d.setStage(l.stage());
            d.setSigners(String.join(",", signers));
            d.setCondition(cond);
            d.setRequired(!Boolean.FALSE.equals(l.required()));
            d.setSort(next.size());
            next.add(d);
        }
        if (Boolean.TRUE.equals(r.gate()) && next.stream().noneMatch(d -> BEFORE_DISBURSEMENT.contains(d.getStage()) && d.isRequired()))
            throw ApiException.unprocessable("NOTHING_TO_SIGN", "Add at least one required document before disbursement to hold disbursement for it");
        mappings.deleteAll(mappings.findByProductCodeOrderBySortAscIdAsc(productCode));
        mappings.flush();
        mappings.saveAll(next);
        if (r.gate() != null) jdbc.update("update product set doc_gate = ? where code = ?", r.gate(), productCode);
        audit.record(by, "product.documents_changed", "PRODUCT", productCode, null, next.size() + " documents" + (r.gate() == null ? "" : ", disbursement waits: " + r.gate()) + " · " + r.note().trim());
        return productDocs(productCode);
    }

    // ---------------------------------------------------------------- the pack

    record Signer(String role, Long partyId, String name) {}

    static boolean applies(String condition, Map<String, Object> values) {
        @SuppressWarnings("unchecked") Map<String, Object> count = (Map<String, Object>) values.get("count");
        int co = ((Number) count.get("coApplicants")).intValue(), gu = ((Number) count.get("guarantors")).intValue(), col = ((Number) count.get("collaterals")).intValue();
        return switch (condition) {
            case "HAS_CO_APPLICANT" -> co > 0;
            case "HAS_GUARANTOR" -> gu > 0;
            case "HAS_COLLATERAL" -> col > 0;
            case "UNSECURED" -> col == 0;
            default -> true;
        };
    }

    @SuppressWarnings("unchecked")
    static List<Signer> signersFor(List<String> roles, Map<String, Object> values) {
        List<Signer> out = new ArrayList<>();
        for (String r : roles) {
            if ("BORROWER".equals(r)) out.add(new Signer("BORROWER", null, (String) ((Map<String, Object>) values.get("applicant")).get("name")));
            else for (Map<String, Object> p : (List<Map<String, Object>>) values.get("CO_APPLICANT".equals(r) ? "coApplicants" : "guarantors"))
                out.add(new Signer(r, ((Number) p.get("id")).longValue(), (String) p.get("name")));
        }
        return out;
    }

    /** Fingerprint of the values a document uses (not today's date, which moves every day). */
    static String dataHash(Map<String, Object> values, Set<String> paths) {
        return sha(Json.write(DocTemplateEngine.pick(values, paths)).getBytes(StandardCharsets.UTF_8));
    }

    static Set<String> fieldsOf(AppDocument d) { return d.getFields() == null || d.getFields().isBlank() ? Set.of() : new TreeSet<>(List.of(d.getFields().split(","))); }

    /** True when the details this document shows have changed since it was made. */
    static boolean outdated(AppDocument d, Map<String, Object> values) { return !d.getDataHash().equals(dataHash(values, fieldsOf(d))); }

    static String sha(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Why a stage's documents cannot be made yet; null when they can. */
    static String notYet(String stage, LoanApplication a, boolean sanctioned) {
        return switch (stage) {
            case "SANCTION", "PRE_DISBURSE" -> sanctioned ? null : "after sanction";
            case "POST_DISBURSE" -> "DISBURSED".equals(a.getAppState()) ? null : "after disbursement";
            case "CLOSURE" -> "DISBURSED".equals(a.getAppState()) ? null : "after the loan is closed";
            default -> "DRAFT".equals(a.getAppState()) ? "after the application is submitted" : null;
        };
    }

    public Map<String, Object> pack(Long appId, CurrentUser by) {
        library.ensureDefaults();
        LoanApplication a = apps.get(appId);
        Map<String, Object> values = data.of(a);
        boolean sanctioned = !((Map<?, ?>) values.get("sanction")).isEmpty();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProductDocument m : mappings.findByProductCodeOrderBySortAscIdAsc(a.getProductCode())) {
            if (!applies(m.getCondition(), values)) continue;
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("templateCode", m.getTemplateCode());
            x.put("name", approvedName(m.getTemplateCode()));
            x.put("stage", m.getStage());
            x.put("required", m.isRequired());
            x.put("notYet", notYet(m.getStage(), a, sanctioned));
            AppDocument d = docs.findByApplicationIdAndTemplateCodeOrderByIdDesc(appId, m.getTemplateCode()).stream().filter(z -> "CURRENT".equals(z.getStatus())).findFirst().orElse(null);
            List<Signer> need = signersFor(splitSigners(m.getSigners()), values);
            List<AppDocumentSignature> signed = d == null ? List.of() : signatures.findByAppDocumentIdOrderByIdAsc(d.getId());
            List<Map<String, Object>> sx = new ArrayList<>();
            for (Signer s : need) {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("role", s.role());
                y.put("partyId", s.partyId());
                y.put("name", s.name());
                signed.stream().filter(z -> z.getSignerRole().equals(s.role()) && Objects.equals(z.getPartyId(), s.partyId())).findFirst()
                        .ifPresent(z -> { y.put("signedAt", z.getSignedAt()); y.put("channel", z.getChannel()); });
                sx.add(y);
            }
            x.put("signers", sx);
            if (d != null) {
                x.put("document", d);
                x.put("outdated", outdated(d, values));
                x.put("missing", d.getMissing() == null || d.getMissing().isBlank() ? List.of() : List.of(d.getMissing().split(", ")));
            }
            x.put("complete", d != null && !outdated(d, values) && sx.stream().allMatch(y -> y.containsKey("signedAt")));
            rows.add(x);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("documents", rows);
        out.put("gate", gate(a.getProductCode()));
        out.put("blockers", blockers(a));
        out.put("stages", STAGES);
        out.put("renderer", renderer.name());
        out.put("superseded", docs.findByApplicationIdOrderByIdAsc(appId).stream().filter(z -> "SUPERSEDED".equals(z.getStatus())).count());
        return out;
    }

    public record GenerateRequest(String stage, String templateCode, Boolean latestVersion, String note) {}

    /** Makes (or re-makes) the documents of a stage, or one document. */
    @Transactional
    public List<AppDocument> generate(Long appId, GenerateRequest r, CurrentUser by) {
        if (!MAKE.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Your role does not make loan documents");
        library.ensureDefaults();
        LoanApplication a = apps.get(appId);
        if (Set.of("REJECTED", "WITHDRAWN").contains(a.getAppState())) throw ApiException.conflict("CLOSED", "The application is " + a.getAppState().toLowerCase());
        Map<String, Object> values = data.of(a);
        boolean sanctioned = !((Map<?, ?>) values.get("sanction")).isEmpty();
        boolean latest = Boolean.TRUE.equals(r.latestVersion());
        if (latest && (r.note() == null || r.note().trim().length() < 10)) throw ApiException.unprocessable("REASON_REQUIRED", "Say why the newer template version is used (at least 10 characters)");
        List<AppDocument> made = new ArrayList<>();
        for (ProductDocument m : mappings.findByProductCodeOrderBySortAscIdAsc(a.getProductCode())) {
            if (r.templateCode() != null ? !r.templateCode().equals(m.getTemplateCode()) : !m.getStage().equals(r.stage())) continue;
            if (!applies(m.getCondition(), values)) {
                if (r.templateCode() != null) throw ApiException.conflict("NOT_APPLICABLE", "This document does not apply to this application (" + m.getCondition().toLowerCase().replace('_', ' ') + ")");
                continue;
            }
            String why = notYet(m.getStage(), a, sanctioned);
            if (why != null) throw ApiException.conflict("NOT_YET", approvedName(m.getTemplateCode()) + " can be made only " + why);
            made.add(make(a, m, values, latest, by));
        }
        if (made.isEmpty()) throw ApiException.unprocessable("NOTHING_TO_MAKE", "No documents for this product at " + (r.templateCode() != null ? r.templateCode() : r.stage()));
        audit.record(by, "documents.generated", "APPLICATION", a.getAppNo(), appId, made.stream().map(d -> d.getTemplateCode() + " v" + d.getTemplateVersion() + " " + d.getLanguage()).reduce((x, y) -> x + ", " + y).orElse("")
                + (latest ? " · newer template: " + r.note().trim() : ""));
        return made;
    }

    AppDocument make(LoanApplication a, ProductDocument m, Map<String, Object> values, boolean latest, CurrentUser by) {
        // the application keeps the template version (and language) it was first made with
        List<AppDocument> earlier = docs.findByApplicationIdAndTemplateCodeOrderByIdDesc(a.getId(), m.getTemplateCode());
        DocTemplate t = null;
        if (!latest && !earlier.isEmpty()) t = templates.findById(earlier.get(earlier.size() - 1).getTemplateId()).orElse(null);
        if (t == null) {
            String lang = a.getPreferredLanguage() == null ? "en" : a.getPreferredLanguage();
            t = templates.findFirstByCodeAndLanguageAndStatusOrderByVersionDesc(m.getTemplateCode(), lang, "APPROVED")
                    .or(() -> templates.findFirstByCodeAndLanguageAndStatusOrderByVersionDesc(m.getTemplateCode(), "en", "APPROVED"))
                    .orElseThrow(() -> ApiException.conflict("NO_TEMPLATE", "No approved template for " + m.getTemplateCode()));
        }
        final String lang = t.getLanguage();
        DocTemplateEngine.Result res;
        try { res = DocTemplateEngine.render(t.getBody(), values, n -> library.partial(n, lang)); }
        catch (DocTemplateEngine.TemplateError e) { throw ApiException.unprocessable("TEMPLATE_INVALID", t.getCode() + " v" + t.getVersion() + ": " + e.getMessage()); }
        byte[] pdf;
        try { pdf = renderer.pdf(res.html(), t.getName() + " · " + a.getAppNo()); }
        catch (VendorException e) { throw ApiException.conflict("VENDOR_FAILED", "The PDF could not be made: " + e.getMessage()); }
        for (AppDocument old : earlier) if ("CURRENT".equals(old.getStatus())) { old.setStatus("SUPERSEDED"); docs.save(old); }
        AppDocument d = new AppDocument();
        d.setApplicationId(a.getId());
        d.setTemplateCode(t.getCode());
        d.setTemplateId(t.getId());
        d.setTemplateVersion(t.getVersion());
        d.setLanguage(lang);
        d.setStage(m.getStage());
        d.setName(t.getName());
        d.setHtml(res.html());
        d.setStoragePath(storage.store(a.getId(), pdf, "pdf"));
        d.setSha256(sha(pdf));
        Set<String> paths = DocTemplateEngine.paths(t.getBody(), n -> library.partial(n, lang));
        d.setFields(String.join(",", paths));
        d.setDataHash(dataHash(values, paths));
        d.setBytes(pdf.length);
        d.setSigners(m.getSigners());
        String missing = String.join(", ", res.missing());
        d.setMissing(missing.length() > 1000 ? missing.substring(0, 1000) : missing);
        d.setGeneratedBy(by.username());
        return docs.save(d);
    }

    public AppDocument document(Long id) { return docs.findById(id).orElseThrow(() -> ApiException.notFound("Document")); }

    public byte[] pdf(AppDocument d) { return storage.read(d.getStoragePath()); }

    public record SignRequest(String role, Long partyId, String otp, String channel) {}

    /** A signer confirms with the OTP sent to their own mobile (demo OTP until SMS/e-sign is integrated). */
    @Transactional
    public AppDocumentSignature sign(Long docId, SignRequest r, CurrentUser by) {
        if (!SIGN.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Your role does not record signatures");
        AppDocument d = document(docId);
        LoanApplication a = apps.get(d.getApplicationId());
        if (!"CURRENT".equals(d.getStatus())) throw ApiException.conflict("SUPERSEDED", "This version was replaced: sign the current one");
        Map<String, Object> values = data.of(a);
        if (outdated(d, values)) throw ApiException.conflict("OUTDATED", "The application changed after this document was made: make it again before signing");
        Signer s = signersFor(splitSigners(d.getSigners()), values).stream().filter(x -> x.role().equals(r.role()) && Objects.equals(x.partyId(), r.partyId())).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("NOT_A_SIGNER", "That person does not sign this document"));
        if (signatures.findByAppDocumentIdOrderByIdAsc(docId).stream().anyMatch(z -> z.getSignerRole().equals(s.role()) && Objects.equals(z.getPartyId(), s.partyId())))
            throw ApiException.conflict("ALREADY_SIGNED", s.name() + " has already signed");
        String channel = r.channel() == null ? "OTP" : r.channel().toUpperCase();
        if (!Set.of("OTP", "WET_INK").contains(channel)) throw ApiException.unprocessable("BAD_CHANNEL", "Sign by OTP, or record a wet-ink signature");
        if ("OTP".equals(channel) && (r.otp() == null || !r.otp().equals(demoOtp))) throw ApiException.unprocessable("BAD_OTP", "The OTP is not correct");
        if ("WET_INK".equals(channel) && !Set.of(Role.OPERATIONS, Role.ADMIN).contains(by.role()))
            throw ApiException.forbidden("NOT_ALLOWED", "Operations records wet-ink signatures after checking the signed original");
        AppDocumentSignature z = new AppDocumentSignature();
        z.setAppDocumentId(docId);
        z.setSignerRole(s.role());
        z.setPartyId(s.partyId());
        z.setSignerName(s.name());
        z.setChannel(channel);
        z.setDocSha256(d.getSha256());
        z.setRecordedBy(by.username());
        signatures.save(z);
        audit.record(by, "documents.signed", "APPLICATION", a.getAppNo(), a.getId(), d.getTemplateCode() + " v" + d.getTemplateVersion() + " · " + s.name() + " (" + s.role().toLowerCase().replace('_', '-') + ") · " + channel);
        return z;
    }

    /** What holds disbursement when the product requires its documents signed first. */
    public List<String> blockers(LoanApplication a) {
        if (!gate(a.getProductCode())) return List.of();
        Map<String, Object> values = data.of(a);
        List<String> out = new ArrayList<>();
        for (ProductDocument m : mappings.findByProductCodeOrderBySortAscIdAsc(a.getProductCode())) {
            if (!m.isRequired() || !BEFORE_DISBURSEMENT.contains(m.getStage()) || !applies(m.getCondition(), values)) continue;
            String name = approvedName(m.getTemplateCode());
            AppDocument d = docs.findByApplicationIdAndTemplateCodeOrderByIdDesc(a.getId(), m.getTemplateCode()).stream().filter(z -> "CURRENT".equals(z.getStatus())).findFirst().orElse(null);
            if (d == null) { out.add(name + " has not been made"); continue; }
            if (outdated(d, values)) { out.add(name + " is out of date (the application changed): make it again"); continue; }
            List<AppDocumentSignature> signed = signatures.findByAppDocumentIdOrderByIdAsc(d.getId());
            for (Signer s : signersFor(splitSigners(d.getSigners()), values))
                if (signed.stream().noneMatch(z -> z.getSignerRole().equals(s.role()) && Objects.equals(z.getPartyId(), s.partyId())))
                    out.add(name + ": waiting for " + s.name() + "'s signature");
        }
        return out;
    }
}
