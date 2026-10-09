package com.rhythm.los.document;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.util.*;

/**
 * Document upload and verification. Verification here is a mock of Document AI: it checks file type
 * and size, and flags names containing "blur" (unreadable) or "tamper" (edited). Phase 3 replaces it with OCR
 * and real tamper detection.
 */
@Service
public class DocumentService {
    public static final Set<String> TYPES = Set.of("PAN", "AADHAAR", "UDYAM", "SALARY_SLIP", "BANK_STATEMENT", "GST_CERT", "RENT_AGREEMENT", "PHOTO");
    private static final Set<String> MIME = Set.of("application/pdf", "image/jpeg", "image/png");

    private final AppDocumentRepository docs;
    private final StorageService storage;
    private final ApplicationService apps;
    private final AuditService audit;

    public DocumentService(AppDocumentRepository docs, StorageService storage, ApplicationService apps, AuditService audit) {
        this.docs = docs;
        this.storage = storage;
        this.apps = apps;
        this.audit = audit;
    }

    public List<AppDocument> list(Long appId) { return docs.findByApplicationIdOrderByIdAsc(appId); }

    @Transactional
    public AppDocument upload(Long appId, String type, MultipartFile file, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a) || "SANCTIONED".equals(a.getAppState()))
            throw ApiException.conflict("WRONG_STAGE", "Documents cannot be added at stage " + a.getAppState());
        if (!TYPES.contains(type)) throw ApiException.unprocessable("BAD_DOC_TYPE", "Unknown document type " + type);
        if (file == null || file.isEmpty()) throw ApiException.unprocessable("EMPTY_FILE", "The file is empty");
        String ct = file.getContentType() == null ? "" : file.getContentType();
        if (!MIME.contains(ct)) throw ApiException.unprocessable("BAD_FILE_TYPE", "Only PDF, JPG and PNG files are accepted");
        try {
            byte[] bytes = file.getBytes();
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("document");
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).toLowerCase() : "";
            AppDocument d = new AppDocument();
            d.setApplicationId(appId);
            d.setDocType(type);
            d.setFileName(name.length() > 200 ? name.substring(0, 200) : name);
            d.setContentType(ct);
            d.setSizeBytes(bytes.length);
            d.setSha256(sha256(bytes));
            d.setStoragePath(storage.store(appId, bytes, ext.length() <= 5 ? ext : ""));
            d.setStatus("UPLOADED");
            d.setUploadedBy(by.username());
            AppDocument saved = docs.save(d);
            audit.record(by, "document.uploaded", "DOCUMENT", String.valueOf(saved.getId()), appId, type + " · " + name + " · sha256 " + saved.getSha256().substring(0, 12));
            return saved;
        } catch (IOException e) {
            throw ApiException.unprocessable("UPLOAD_FAILED", "Could not read the uploaded file");
        }
    }

    public byte[] content(AppDocument d) { return storage.read(d.getStoragePath()); }

    public AppDocument get(Long docId) { return docs.findById(docId).orElseThrow(() -> ApiException.notFound("Document")); }

    /** Runs mock Document AI on unverified files and updates the documents state. */
    @Transactional
    public LoanApplication verify(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a)) throw ApiException.conflict("WRONG_STAGE", "Application is closed");
        if ("COMPLETE".equals(a.getDocsState())) return a;
        for (AppDocument d : list(appId)) {
            if (!"UPLOADED".equals(d.getStatus())) continue;
            String n = d.getFileName().toLowerCase();
            if (n.contains("blur")) {
                d.setStatus("REJECTED");
                d.setRemarks("Unreadable: image is blurred. Please upload a clear copy.");
                d.setReadConfidence(new BigDecimal("0.410"));
            } else {
                d.setStatus("VERIFIED");
                d.setReadConfidence(new BigDecimal("0.960"));
                if (n.contains("tamper")) {
                    d.setTamperFlag(true);
                    d.setRemarks("Possible edit detected: fonts and metadata do not match the issuer template");
                }
            }
            docs.save(d);
            audit.record(by, "document.verified", "DOCUMENT", String.valueOf(d.getId()), appId, d.getDocType() + " -> " + d.getStatus());
        }
        List<String> missing = missing(a);
        if (missing.isEmpty()) {
            apps.transition(a, Domain.DOCS, "COMPLETE", "docs.complete", by, "All required documents verified");
        } else if (!"DEFICIENT".equals(a.getDocsState())) {
            apps.transition(a, Domain.DOCS, "DEFICIENT", "docs.deficient", by, "Missing or rejected: " + String.join(", ", missing));
        }
        return a;
    }

    public List<String> missing(LoanApplication a) {
        Set<String> verified = new HashSet<>();
        for (AppDocument d : list(a.getId())) if ("VERIFIED".equals(d.getStatus())) verified.add(d.getDocType());
        List<String> out = new ArrayList<>();
        for (String t : apps.product(a).requiredDocList()) if (!verified.contains(t)) out.add(t);
        return out;
    }

    public boolean anyTampered(Long appId) {
        return list(appId).stream().anyMatch(AppDocument::isTamperFlag);
    }

    private static String sha256(byte[] b) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
