package com.rhythm.los.docgen;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Document templates (Admin → Document templates), product documents (Products → Documents) and application document packs. */
@RestController
@RequestMapping("/api")
public class DocController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','COMPLIANCE','LEGAL_REVIEWER','FIELD_OFFICER','FRAUD_ANALYST','VALUER')";
    static final String LIBRARY = "hasAnyRole('ADMIN','COMPLIANCE','CREDIT_MANAGER','CRO','LEGAL_REVIEWER','OPERATIONS')";

    private final DocTemplateService library;
    private final DocPackService packs;

    public DocController(DocTemplateService library, DocPackService packs) {
        this.library = library;
        this.packs = packs;
    }

    // templates
    @GetMapping("/doc-templates")
    @PreAuthorize(LIBRARY)
    public Map<String, Object> library() { return library.library(); }

    @GetMapping("/doc-templates/{id}")
    @PreAuthorize(LIBRARY)
    public Map<String, Object> template(@PathVariable Long id) { return library.detail(id); }

    @PostMapping("/doc-templates")
    @PreAuthorize(LIBRARY)
    public DocTemplate create(@RequestBody DocTemplateService.DraftRequest r) { return library.createDraft(r, CurrentUser.get()); }

    @PostMapping(value = "/doc-templates/word", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(LIBRARY)
    public Map<String, Object> word(@RequestPart("file") MultipartFile file, @RequestParam(required = false) String code, @RequestParam(required = false) String name,
                                    @RequestParam(required = false) String docType, @RequestParam(required = false) String language,
                                    @RequestParam(required = false) Long fromId, @RequestParam(required = false) String notes) throws Exception {
        return library.importWord(file.getBytes(), new DocTemplateService.DraftRequest(code, name, docType, language, null, notes, fromId), CurrentUser.get());
    }

    @PutMapping("/doc-templates/{id}")
    @PreAuthorize(LIBRARY)
    public DocTemplate update(@PathVariable Long id, @RequestBody DocTemplateService.UpdateRequest r) { return library.update(id, r, CurrentUser.get()); }

    @PostMapping("/doc-templates/{id}/submit")
    @PreAuthorize(LIBRARY)
    public DocTemplate submit(@PathVariable Long id) { return library.submit(id, CurrentUser.get()); }

    @PostMapping("/doc-templates/{id}/decision")
    @PreAuthorize("hasAnyRole('COMPLIANCE','CRO')")
    public DocTemplate decide(@PathVariable Long id, @RequestBody DocTemplateService.Decision d) { return library.decide(id, d, CurrentUser.get()); }

    @DeleteMapping("/doc-templates/{id}")
    @PreAuthorize(LIBRARY)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        library.deleteDraft(id, CurrentUser.get());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/doc-templates/preview")
    @PreAuthorize(LIBRARY)
    public Map<String, Object> preview(@RequestBody DocTemplateService.PreviewRequest r) { return library.preview(r, CurrentUser.get()); }

    @PostMapping(value = "/doc-templates/preview.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(LIBRARY)
    public ResponseEntity<byte[]> previewPdf(@RequestBody DocTemplateService.PreviewRequest r) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=preview.pdf").contentType(MediaType.APPLICATION_PDF).body(library.previewPdf(r, CurrentUser.get()));
    }

    // product documents
    @GetMapping("/products/{code}/documents")
    @PreAuthorize(STAFF)
    public Map<String, Object> productDocs(@PathVariable String code) { return packs.productDocs(code); }

    @PutMapping("/products/{code}/documents")
    @PreAuthorize("hasAnyRole('ADMIN','CRO','COMPLIANCE')")
    public Map<String, Object> setProductDocs(@PathVariable String code, @RequestBody DocPackService.ProductDocsRequest r) { return packs.setProductDocs(code, r, CurrentUser.get()); }

    // application packs
    @GetMapping("/applications/{id}/documents-pack")
    @PreAuthorize(STAFF)
    public Map<String, Object> pack(@PathVariable Long id) { return packs.pack(id, CurrentUser.get()); }

    @PostMapping("/applications/{id}/documents-pack/generate")
    @PreAuthorize(STAFF)
    public List<AppDocument> generate(@PathVariable Long id, @RequestBody DocPackService.GenerateRequest r) { return packs.generate(id, r, CurrentUser.get()); }

    @GetMapping(value = "/app-documents/{docId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(STAFF)
    public ResponseEntity<byte[]> pdf(@PathVariable Long docId) {
        AppDocument d = packs.document(docId);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + d.getTemplateCode().toLowerCase() + "-v" + d.getTemplateVersion() + ".pdf")
                .contentType(MediaType.APPLICATION_PDF).body(packs.pdf(d));
    }

    /** The filled-in HTML (shown by the web in a sandboxed frame, never as a page of this site). */
    @GetMapping("/app-documents/{docId}/html")
    @PreAuthorize(STAFF)
    public Map<String, Object> html(@PathVariable Long docId) {
        AppDocument d = packs.document(docId);
        return Map.of("name", d.getName(), "html", d.getHtml());
    }

    @PostMapping("/app-documents/{docId}/sign")
    @PreAuthorize(STAFF)
    public AppDocumentSignature sign(@PathVariable Long docId, @RequestBody DocPackService.SignRequest r) { return packs.sign(docId, r, CurrentUser.get()); }
}
