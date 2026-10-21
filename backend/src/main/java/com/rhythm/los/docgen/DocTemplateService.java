package com.rhythm.los.docgen;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.integration.VendorException;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * The template library: loan documents and clauses, each a versioned HTML template with fields. Drafted in the browser or
 * imported from Word, checked, approved by compliance or the CRO (never by the person who drafted it), then used by
 * products. Approving a version retires the earlier approved one; applications keep the version they started with.
 */
@Service
public class DocTemplateService {
    public static final Set<String> TYPES = new LinkedHashSet<>(List.of("APPLICATION_FORM", "SANCTION_LETTER", "KFS", "LOAN_AGREEMENT", "GUARANTEE_DEED",
            "DPN", "HYPOTHECATION", "MORTGAGE", "WELCOME", "NOC", "OTHER", "CLAUSE"));
    public static final Set<String> LANGUAGES = Set.of("en", "hi", "mr", "ta", "ml", "kn", "te", "bn", "gu");
    static final Set<Role> MAKERS = EnumSet.of(Role.ADMIN, Role.COMPLIANCE, Role.CREDIT_MANAGER, Role.LEGAL_REVIEWER, Role.CRO);
    static final Set<Role> CHECKERS = EnumSet.of(Role.COMPLIANCE, Role.CRO);

    private final DocTemplateRepository repo;
    private final ProductDocumentRepository mappings;
    private final DocData data;
    private final DocRenderer renderer;
    private final ApplicationService apps;
    private final AuditService audit;
    private final com.rhythm.los.product.ProductRepository products;

    public DocTemplateService(DocTemplateRepository repo, ProductDocumentRepository mappings, DocData data, DocRenderer renderer,
                              ApplicationService apps, AuditService audit, com.rhythm.los.product.ProductRepository products) {
        this.repo = repo;
        this.mappings = mappings;
        this.data = data;
        this.renderer = renderer;
        this.apps = apps;
        this.audit = audit;
        this.products = products;
    }

    // ---------------------------------------------------------------- starter library

    record Seed(String code, String name, String type) {}

    /** The starter templates and each product's document list, the first time the library is used (per tenant). */
    @Transactional
    public void ensureDefaults() {
        if (repo.count() == 0) {
            try {
                List<Seed> seeds = Json.read(new String(new ClassPathResource("docgen/seed/index.json").getInputStream().readAllBytes(), StandardCharsets.UTF_8),
                        new TypeReference<List<Seed>>() {});
                for (Seed s : seeds) {
                    DocTemplate t = new DocTemplate();
                    t.setCode(s.code());
                    t.setName(s.name());
                    t.setDocType(s.type());
                    t.setLanguage("en");
                    t.setVersion(1);
                    t.setStatus("APPROVED");
                    t.setSource("SYSTEM");
                    t.setBody(new String(new ClassPathResource("docgen/seed/" + s.code() + ".html").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
                    t.setNotes("Starter template: review with your legal team before use");
                    t.setCreatedBy("system");
                    t.setDecidedBy("system");
                    t.setDecidedAt(Instant.now());
                    repo.save(t);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Starter templates missing", e);
            }
        }
        // every product gets the standard pack once (products added later, such as asset finance, get theirs on the next start)
        java.util.Set<String> mapped = new java.util.HashSet<>();
        mappings.findAll().forEach(m -> mapped.add(m.getProductCode()));
        for (var p : products.findAll())
            if (!mapped.contains(p.getCode()))
                for (ProductDocument m : defaultMapping(p.getCode(), p.isSecured(), (p.getCollateralTypes() == null ? "" : p.getCollateralTypes()) + (p.assetClassList().isEmpty() ? "" : ",VEHICLE"))) {
                    // asset finance: the financed asset itself is hypothecated, so the deed is always in the pack
                    if (!p.assetClassList().isEmpty() && "HYPOTHECATION_DEED".equals(m.getTemplateCode())) m.setCondition("ALWAYS");
                    mappings.save(m);
                }
    }

    static List<ProductDocument> defaultMapping(String product, boolean secured, String collateralTypes) {
        List<ProductDocument> out = new ArrayList<>();
        String types = collateralTypes == null ? "" : collateralTypes;
        add(out, product, "APPLICATION_FORM", "APPLICATION", "BORROWER,CO_APPLICANT", "ALWAYS", true);
        add(out, product, "SANCTION_LETTER", "SANCTION", "BORROWER", "ALWAYS", true);
        add(out, product, "KFS", "SANCTION", "BORROWER,CO_APPLICANT,GUARANTOR", "ALWAYS", true);
        add(out, product, "LOAN_AGREEMENT", "PRE_DISBURSE", "BORROWER,CO_APPLICANT", "ALWAYS", true);
        add(out, product, "GUARANTEE_DEED", "PRE_DISBURSE", "GUARANTOR", "HAS_GUARANTOR", true);
        if (!secured && !"GL".equals(product)) add(out, product, "DPN", "PRE_DISBURSE", "BORROWER,CO_APPLICANT", "ALWAYS", true);
        if (types.contains("VEHICLE")) add(out, product, "HYPOTHECATION_DEED", "PRE_DISBURSE", "BORROWER", "HAS_COLLATERAL", true);
        if (types.contains("RESIDENTIAL") || types.contains("SHOP") || types.contains("PLOT"))
            add(out, product, "MORTGAGE_MOD", "PRE_DISBURSE", "BORROWER,CO_APPLICANT", "HAS_COLLATERAL", true);
        add(out, product, "WELCOME_LETTER", "POST_DISBURSE", "", "ALWAYS", false);
        add(out, product, "NOC", "CLOSURE", "", "ALWAYS", false);
        return out;
    }

    private static void add(List<ProductDocument> out, String product, String code, String stage, String signers, String cond, boolean required) {
        ProductDocument d = new ProductDocument();
        d.setProductCode(product);
        d.setTemplateCode(code);
        d.setStage(stage);
        d.setSigners(signers);
        d.setCondition(cond);
        d.setRequired(required);
        d.setSort(out.size());
        out.add(d);
    }

    // ---------------------------------------------------------------- reading

    public Map<String, Object> library() {
        ensureDefaults();
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        for (DocTemplate t : repo.findAllByOrderByCodeAscLanguageAscVersionDesc()) {
            Map<String, Object> m = byCode.computeIfAbsent(t.getCode(), c -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("code", c);
                x.put("name", t.getName());
                x.put("docType", t.getDocType());
                x.put("products", mappings.countByTemplateCode(c));
                x.put("versions", new ArrayList<Map<String, Object>>());
                return x;
            });
            if ("APPROVED".equals(t.getStatus())) m.put("name", t.getName());
            @SuppressWarnings("unchecked") List<Map<String, Object>> vs = (List<Map<String, Object>>) m.get("versions");
            vs.add(summary(t));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templates", byCode.values());
        out.put("types", TYPES);
        out.put("languages", new TreeSet<>(LANGUAGES));
        out.put("fields", DocFields.FIELDS);
        out.put("builtIn", DocFields.BUILT_IN.keySet());
        out.put("renderer", renderer.name());
        return out;
    }

    static Map<String, Object> summary(DocTemplate t) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", t.getId());
        v.put("language", t.getLanguage());
        v.put("version", t.getVersion());
        v.put("status", t.getStatus());
        v.put("source", t.getSource());
        v.put("createdBy", t.getCreatedBy());
        v.put("createdAt", t.getCreatedAt());
        v.put("decidedBy", t.getDecidedBy());
        v.put("decidedAt", t.getDecidedAt());
        return v;
    }

    public DocTemplate get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("Template")); }

    public Map<String, Object> detail(Long id) {
        DocTemplate t = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", t);
        m.put("problems", problems(t.getDocType(), t.getBody(), t.getLanguage()));
        m.put("history", repo.findByCodeAndLanguageOrderByVersionDesc(t.getCode(), t.getLanguage()).stream().map(DocTemplateService::summary).toList());
        m.put("usedBy", mappings.findAll().stream().filter(x -> x.getTemplateCode().equals(t.getCode())).map(ProductDocument::getProductCode).distinct().toList());
        return m;
    }

    /** Clauses and built-in blocks a template may include. */
    Set<String> partialNames() {
        Set<String> s = new TreeSet<>(DocFields.BUILT_IN.keySet());
        for (DocTemplate t : repo.findAllByOrderByCodeAscLanguageAscVersionDesc()) if ("CLAUSE".equals(t.getDocType()) && !"RETIRED".equals(t.getStatus())) s.add(t.getCode());
        return s;
    }

    public List<String> problems(String type, String body, String language) {
        List<String> p = new ArrayList<>(DocTemplateEngine.check(body, DocFields.catalog(), partialNames()));
        if ("KFS".equals(type) && (body == null || !body.replaceAll("\\s", "").contains("{{>KFS_STANDARD}}")))
            p.add("A KFS must include {{> KFS_STANDARD}}: RBI's standard KFS table cannot be changed, only added to");
        if (body != null && body.toLowerCase().matches("(?s).*<\\s*script.*")) p.add("Scripts are not allowed");
        if (body != null && body.length() > 400_000) p.add("The template is too long");
        return p;
    }

    /** A clause or built-in block in the document's language, else English. */
    String partial(String name, String language) {
        if (DocFields.BUILT_IN.containsKey(name)) return DocFields.BUILT_IN.get(name);
        return repo.findFirstByCodeAndLanguageAndStatusOrderByVersionDesc(name, language, "APPROVED")
                .or(() -> repo.findFirstByCodeAndLanguageAndStatusOrderByVersionDesc(name, "en", "APPROVED"))
                .map(DocTemplate::getBody).orElse(null);
    }

    // ---------------------------------------------------------------- drafting

    public record DraftRequest(String code, String name, String docType, String language, String body, String notes, Long fromId) {}

    static void requireMaker(CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Admin, compliance, legal or a credit manager drafts templates");
    }

    /** A new template, or a new draft version (of another language too) of an existing one. */
    @Transactional
    public DocTemplate createDraft(DraftRequest r, CurrentUser by) {
        requireMaker(by);
        ensureDefaults();
        DocTemplate from = r.fromId() == null ? null : get(r.fromId());
        String code = from != null && (r.code() == null || r.code().isBlank()) ? from.getCode() : r.code() == null ? "" : r.code().trim().toUpperCase();
        if (!code.matches("[A-Z][A-Z0-9_]{2,39}")) throw ApiException.unprocessable("BAD_CODE", "Code: capitals, digits and _ (3 to 40), e.g. LAP_AGREEMENT or CL_INSURANCE");
        if (DocFields.BUILT_IN.containsKey(code)) throw ApiException.unprocessable("BAD_CODE", code + " is a built-in block");
        String type = r.docType() != null && !r.docType().isBlank() ? r.docType().trim().toUpperCase() : from != null ? from.getDocType() : "";
        if (!TYPES.contains(type)) throw ApiException.unprocessable("BAD_TYPE", "Type must be one of " + TYPES);
        if (code.startsWith("CL_") != "CLAUSE".equals(type)) throw ApiException.unprocessable("BAD_CODE", "Clause codes start with CL_, and only clauses do");
        String lang = r.language() == null || r.language().isBlank() ? from != null ? from.getLanguage() : "en" : r.language();
        if (!LANGUAGES.contains(lang)) throw ApiException.unprocessable("BAD_LANGUAGE", "Language must be one of " + new TreeSet<>(LANGUAGES));
        List<DocTemplate> existing = repo.findByCodeOrderByVersionDesc(code);
        if (from == null && !existing.isEmpty() && existing.stream().anyMatch(x -> x.getLanguage().equals(lang)))
            throw ApiException.conflict("CODE_EXISTS", code + " already exists in this language: open it and make a new version");
        if (existing.stream().anyMatch(x -> !x.getDocType().equals(type))) throw ApiException.unprocessable("BAD_TYPE", code + " is a " + existing.get(0).getDocType());
        if (repo.findByCodeAndLanguageOrderByVersionDesc(code, lang).stream().anyMatch(x -> Set.of("DRAFT", "PENDING_APPROVAL", "REJECTED").contains(x.getStatus())))
            throw ApiException.conflict("DRAFT_EXISTS", "There is already a draft of " + code + " (" + lang + "): edit that one");
        DocTemplate t = new DocTemplate();
        t.setCode(code);
        t.setName(r.name() != null && !r.name().isBlank() ? r.name().trim() : from != null ? from.getName() : code);
        t.setDocType(type);
        t.setLanguage(lang);
        t.setVersion(repo.findByCodeAndLanguageOrderByVersionDesc(code, lang).stream().mapToInt(DocTemplate::getVersion).max().orElse(0) + 1);
        t.setStatus("DRAFT");
        t.setSource(from != null ? from.getSource().equals("WORD") ? "WORD" : "EDITOR" : "EDITOR");
        t.setBody(r.body() != null ? r.body() : from != null ? from.getBody() : "<h1>" + DocTemplateEngine.escape(t.getName()) + "</h1>\n<p></p>");
        t.setNotes(r.notes());
        t.setCreatedBy(by.username());
        repo.save(t);
        audit.record(by, "doctemplate.draft_created", "TEMPLATE", code + " v" + t.getVersion() + " " + lang, null, from == null ? "new" : "from v" + from.getVersion() + " " + from.getLanguage());
        return t;
    }

    public record UpdateRequest(String name, String body, String notes) {}

    @Transactional
    public DocTemplate update(Long id, UpdateRequest r, CurrentUser by) {
        requireMaker(by);
        DocTemplate t = get(id);
        if (!Set.of("DRAFT", "REJECTED").contains(t.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Only drafts can be edited (this is " + t.getStatus().toLowerCase().replace('_', ' ') + ")");
        if (r.body() != null) {
            if (r.body().length() > 400_000) throw ApiException.unprocessable("TOO_LONG", "The template is too long");
            t.setBody(r.body());
        }
        if (r.name() != null && !r.name().isBlank()) t.setName(r.name().trim());
        if (r.notes() != null) t.setNotes(r.notes().length() > 500 ? r.notes().substring(0, 500) : r.notes());
        t.setStatus("DRAFT");
        return repo.save(t);
    }

    /** A Word (.docx) template with {{fields}} typed in becomes a draft (a new template or a new version). */
    @Transactional
    public Map<String, Object> importWord(byte[] docx, DraftRequest r, CurrentUser by) {
        requireMaker(by);
        if (docx == null || docx.length == 0 || docx.length > 10 * 1024 * 1024) throw ApiException.unprocessable("BAD_FILE", "Upload a Word file up to 10 MB");
        if (docx[0] != 'P' || docx[1] != 'K') throw ApiException.unprocessable("BAD_FILE", "Upload a Word .docx file (not .doc or PDF)");
        DocRenderer.WordImport w;
        try { w = renderer.word(docx); }
        catch (VendorException e) { throw ApiException.conflict("VENDOR_FAILED", e.getMessage()); }
        if (w == null) throw ApiException.conflict("WORD_NOT_AVAILABLE", "Word import needs the AI document service (DOCUMENT_AI_URL)");
        DocTemplate t = createDraft(new DraftRequest(r.code(), r.name(), r.docType(), r.language(), w.html(), r.notes(), r.fromId()), by);
        t.setSource("WORD");
        repo.save(t);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("template", t);
        m.put("fields", w.fields());
        m.put("messages", w.messages());
        m.put("problems", problems(t.getDocType(), t.getBody(), t.getLanguage()));
        audit.record(by, "doctemplate.word_imported", "TEMPLATE", t.getCode() + " v" + t.getVersion(), null, w.fields().size() + " fields");
        return m;
    }

    // ---------------------------------------------------------------- approval

    @Transactional
    public DocTemplate submit(Long id, CurrentUser by) {
        requireMaker(by);
        DocTemplate t = get(id);
        if (!Set.of("DRAFT", "REJECTED").contains(t.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Only drafts can be sent for approval");
        List<String> p = problems(t.getDocType(), t.getBody(), t.getLanguage());
        if (!p.isEmpty()) throw ApiException.unprocessable("TEMPLATE_INVALID", "Fix before sending: " + String.join("; ", p));
        if (t.getNotes() == null || t.getNotes().trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Describe the change in at least 10 characters");
        t.setStatus("PENDING_APPROVAL");
        t.setSubmittedAt(Instant.now());
        audit.record(by, "doctemplate.submitted", "TEMPLATE", t.getCode() + " v" + t.getVersion() + " " + t.getLanguage(), null, t.getNotes());
        return repo.save(t);
    }

    public record Decision(boolean approve, String note) {}

    @Transactional
    public DocTemplate decide(Long id, Decision d, CurrentUser by) {
        if (!CHECKERS.contains(by.role())) throw ApiException.forbidden("NOT_CHECKER", "Compliance or the CRO approves templates");
        DocTemplate t = get(id);
        if (!"PENDING_APPROVAL".equals(t.getStatus())) throw ApiException.conflict("NOT_PENDING", "Template is " + t.getStatus().toLowerCase().replace('_', ' '));
        if (by.username().equals(t.getCreatedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who drafted a template cannot approve it");
        String note = d.note() == null ? "" : d.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        t.setDecidedBy(by.username());
        t.setDecidedAt(Instant.now());
        t.setDecisionNote(note.length() > 500 ? note.substring(0, 500) : note);
        if (d.approve()) {
            for (DocTemplate o : repo.findByCodeAndLanguageOrderByVersionDesc(t.getCode(), t.getLanguage()))
                if ("APPROVED".equals(o.getStatus())) { o.setStatus("RETIRED"); repo.save(o); }
            t.setStatus("APPROVED");
        } else t.setStatus("REJECTED");
        audit.record(by, d.approve() ? "doctemplate.approved" : "doctemplate.rejected", "TEMPLATE", t.getCode() + " v" + t.getVersion() + " " + t.getLanguage(), null, note);
        return repo.save(t);
    }

    @Transactional
    public void deleteDraft(Long id, CurrentUser by) {
        requireMaker(by);
        DocTemplate t = get(id);
        if (!Set.of("DRAFT", "REJECTED").contains(t.getStatus())) throw ApiException.conflict("NOT_DRAFT", "Only drafts can be deleted");
        repo.delete(t);
        audit.record(by, "doctemplate.draft_deleted", "TEMPLATE", t.getCode() + " v" + t.getVersion(), null, null);
    }

    // ---------------------------------------------------------------- preview

    public record PreviewRequest(Long templateId, String body, String docType, String language, Long applicationId) {}

    /** Fills a template (saved or as typed) with an application's details, or with sample values. */
    public Map<String, Object> preview(PreviewRequest r, CurrentUser by) {
        ensureDefaults();
        DocTemplate t = r.templateId() == null ? null : get(r.templateId());
        String body = r.body() != null ? r.body() : t != null ? t.getBody() : "";
        String lang = r.language() != null ? r.language() : t != null ? t.getLanguage() : "en";
        String type = r.docType() != null ? r.docType() : t != null ? t.getDocType() : "OTHER";
        List<String> problems = problems(type, body, lang);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("problems", problems);
        if (problems.stream().anyMatch(x -> !x.startsWith("Unknown field") && !x.startsWith("No clause"))) { m.put("html", ""); m.put("missing", List.of()); return m; }
        Map<String, Object> values = r.applicationId() == null ? DocFields.sample() : data.of(apps.get(r.applicationId()));
        DocTemplateEngine.Result res = DocTemplateEngine.render(body, values, n -> partial(n, lang));
        m.put("html", res.html());
        m.put("missing", res.missing());
        m.put("with", r.applicationId() == null ? "sample values" : "application " + values.get("app"));
        return m;
    }

    public byte[] previewPdf(PreviewRequest r, CurrentUser by) {
        Map<String, Object> p = preview(r, by);
        String html = (String) p.get("html");
        if (html == null || html.isBlank()) throw ApiException.unprocessable("TEMPLATE_INVALID", "Fix the template first: " + p.get("problems"));
        try { return renderer.pdf(html, r.templateId() == null ? "Preview" : get(r.templateId()).getName()); }
        catch (VendorException e) { throw ApiException.conflict("VENDOR_FAILED", e.getMessage()); }
    }
}
