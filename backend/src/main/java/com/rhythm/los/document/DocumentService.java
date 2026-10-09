package com.rhythm.los.document;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.integration.IntegrationGateway;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Document upload, AI reading and human review.
 *
 * Every unread upload is sent to the DocumentReader (AI service, or the offline mock) through the integration
 * gateway. Results: OK -> VERIFIED, NEEDS_REVIEW -> waits for an operations reviewer, UNREADABLE -> REJECTED.
 * For Aadhaar, the stored file is replaced by the masked image the AI returns.
 */
@Service
public class DocumentService {
    public static final Set<String> TYPES = Set.of("PAN", "AADHAAR", "VOTER_ID", "DRIVING_LICENCE", "PASSPORT", "UDYAM",
            "SALARY_SLIP", "BANK_STATEMENT", "GST_CERT", "RENT_AGREEMENT", "PHOTO");
    private static final Set<String> MIME = Set.of("application/pdf", "image/jpeg", "image/png");

    private final AppDocumentRepository docs;
    private final StorageService storage;
    private final ApplicationService apps;
    private final AuditService audit;
    private final DocumentReader reader;
    private final IntegrationGateway gateway;

    public DocumentService(AppDocumentRepository docs, StorageService storage, ApplicationService apps, AuditService audit,
                           DocumentReader reader, IntegrationGateway gateway) {
        this.docs = docs;
        this.storage = storage;
        this.apps = apps;
        this.audit = audit;
        this.reader = reader;
        this.gateway = gateway;
    }

    public List<AppDocument> list(Long appId) { return docs.findByApplicationIdOrderByIdAsc(appId); }

    public boolean aiEnabled() { return reader.extractsFields(); }

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

    /** Reads every unread document with the AI service and updates the documents state. */
    @Transactional
    public LoanApplication verify(Long appId, CurrentUser by) {
        LoanApplication a = apps.get(appId);
        if (!ApplicationService.isOpen(a)) throw ApiException.conflict("WRONG_STAGE", "Application is closed");
        if ("COMPLETE".equals(a.getDocsState())) return a;
        for (AppDocument d : list(appId)) {
            if (!"UPLOADED".equals(d.getStatus())) continue;
            byte[] bytes = storage.read(d.getStoragePath());
            var out = gateway.call(appId, reader.name(), "document.read",
                    () -> reader.read(bytes, d.getContentType(), d.getFileName(), d.getDocType()),
                    r -> d.getDocType() + " read as " + r.documentType() + " · " + r.status() + " · " + Math.round(r.overallConfidence() * 100) + "%", null);
            if (!out.ok()) {
                d.setRemarks("Could not be read automatically (" + out.error() + "). Retry later or review manually.");
                d.setStatus("NEEDS_REVIEW");
                d.setAiStatus("ERROR");
                docs.save(d);
                continue;
            }
            apply(d, out.value());
            docs.save(d);
            audit.record(by, "document.read", "DOCUMENT", String.valueOf(d.getId()), appId,
                    d.getDocType() + " -> " + d.getStatus() + (d.getRemarks() == null ? "" : " · " + d.getRemarks()));
        }
        updateDocsState(a, by);
        return a;
    }

    void apply(AppDocument d, DocumentReading r) {
        d.setAiStatus(r.status());
        d.setDetectedType(r.documentType());
        d.setTypeConfidence(bd(r.typeConfidence()));
        d.setReadConfidence(bd(r.overallConfidence()));
        d.setExtractedJson(Json.write(r.fields()));
        d.setValidationsJson(Json.write(r.validations()));
        d.setQualityJson(Json.write(r.quality()));
        d.setTamperJson(Json.write(r.tamper()));
        d.setReviewReasons(Json.write(r.reviewReasons()));
        d.setEngine(r.engine());
        d.setTamperFlag(r.tamperScore() >= 0.5);
        if (r.masked() && r.maskedImage() != null) {
            // keep only the masked Aadhaar image; the upload hash stays as evidence of what was received
            String old = d.getStoragePath();
            d.setStoragePath(storage.store(d.getApplicationId(), r.maskedImage(), ".png"));
            d.setContentType("image/png");
            d.setFileName(d.getFileName().replaceAll("\\.[A-Za-z0-9]+$", "") + "-masked.png");
            d.setSizeBytes(r.maskedImage().length);
            d.setMasked(true);
            storage.delete(old);
        }
        switch (r.status()) {
            case "OK" -> { d.setStatus("VERIFIED"); d.setRemarks(r.tamperScore() >= 0.5 ? String.join("; ", signals(r)) : null); }
            case "UNREADABLE" -> { d.setStatus("REJECTED"); d.setRemarks("Unreadable: " + String.join("; ", r.reviewReasons()) + ". Please upload a clear copy."); }
            default -> { d.setStatus("NEEDS_REVIEW"); d.setRemarks(String.join("; ", r.reviewReasons())); }
        }
        if (d.getRemarks() != null && d.getRemarks().length() > 300) d.setRemarks(d.getRemarks().substring(0, 297) + "...");
    }

    @SuppressWarnings("unchecked")
    private static List<String> signals(DocumentReading r) {
        Object s = r.tamper() == null ? null : r.tamper().get("signals");
        return s instanceof List<?> l ? (List<String>) l : List.of();
    }

    public record ReviewRequest(String action, Map<String, String> fields, String note) {}

    /** A reviewer approves (optionally correcting fields) or rejects a document the AI was unsure about. */
    @Transactional
    public AppDocument review(Long appId, Long docId, ReviewRequest req, CurrentUser by) {
        AppDocument d = get(docId);
        if (!d.getApplicationId().equals(appId)) throw ApiException.notFound("Document");
        if (!Set.of("NEEDS_REVIEW", "VERIFIED").contains(d.getStatus()))
            throw ApiException.conflict("NOT_REVIEWABLE", "Document is " + d.getStatus());
        if (req.note() == null || req.note().trim().length() < 10)
            throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        boolean approve = "APPROVE".equalsIgnoreCase(req.action());
        if (approve && req.fields() != null && !req.fields().isEmpty()) {
            Map<String, DocumentReading.FieldValue> f = new LinkedHashMap<>(extracted(d));
            for (var e : req.fields().entrySet()) {
                String k = e.getKey(), v = e.getValue() == null ? "" : e.getValue().trim();
                if (k.toLowerCase().contains("aadhaar") && v.replaceAll("\\D", "").length() > 4)
                    throw ApiException.unprocessable("AADHAAR_UNMASKED", "Enter only the last 4 digits of the Aadhaar number");
                if (!v.isEmpty()) f.put(k, new DocumentReading.FieldValue(v, 1.0, "reviewer"));
            }
            d.setExtractedJson(Json.write(f));
        }
        if (approve && "AADHAAR".equals(d.getDocType()) && aiEnabled() && !d.isMasked())
            throw ApiException.unprocessable("AADHAAR_UNMASKED", "This Aadhaar image is not masked. Reject it and ask for a masked copy (first 8 digits hidden).");
        d.setStatus(approve ? "VERIFIED" : "REJECTED");
        d.setReviewedBy(by.username());
        d.setReviewedAt(Instant.now());
        d.setReviewNote(req.note());
        if (!approve) d.setRemarks("Rejected by reviewer: " + req.note());
        docs.save(d);
        audit.record(by, approve ? "document.review_approved" : "document.review_rejected", "DOCUMENT", String.valueOf(docId), appId,
                d.getDocType() + " · " + req.note() + (req.fields() == null || req.fields().isEmpty() ? "" : " · corrected " + req.fields().keySet()));
        LoanApplication a = apps.get(appId);
        updateDocsState(a, by);
        return d;
    }

    /** Recomputes the documents state from the documents on file. */
    public void updateDocsState(LoanApplication a, CurrentUser by) {
        List<String> missing = missing(a);
        boolean reviewPending = list(a.getId()).stream().anyMatch(x -> "NEEDS_REVIEW".equals(x.getStatus()));
        String to = missing.isEmpty() ? "COMPLETE" : reviewPending ? "IN_REVIEW" : "DEFICIENT";
        if (to.equals(a.getDocsState())) return;
        String note = switch (to) {
            case "COMPLETE" -> "All required documents verified";
            case "IN_REVIEW" -> "Waiting for document review: " + String.join(", ", missing);
            default -> "Missing or rejected: " + String.join(", ", missing);
        };
        if ("COMPLETE".equals(a.getDocsState())) return; // a verified set is not reopened here
        apps.transition(a, Domain.DOCS, to, "docs." + to.toLowerCase(), by, note);
    }

    /** Required document groups still not verified. A group like AADHAAR|VOTER_ID is met by any one of them. */
    public List<String> missing(LoanApplication a) {
        Set<String> verified = new HashSet<>();
        for (AppDocument d : list(a.getId())) if ("VERIFIED".equals(d.getStatus())) verified.add(d.getDocType());
        List<String> out = new ArrayList<>();
        for (String group : apps.product(a).requiredDocList()) {
            boolean met = Arrays.stream(group.split("\\|")).map(String::trim).anyMatch(verified::contains);
            if (!met) out.add(group.replace("|", " or "));
        }
        return out;
    }

    public boolean anyTampered(Long appId) {
        return list(appId).stream().anyMatch(x -> x.isTamperFlag() && !"REJECTED".equals(x.getStatus()));
    }

    public Map<String, DocumentReading.FieldValue> extracted(AppDocument d) {
        if (d.getExtractedJson() == null) return Map.of();
        return Json.read(d.getExtractedJson(), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, DocumentReading.FieldValue>>() {});
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(Math.max(0, Math.min(0.999, v))).setScale(3, RoundingMode.HALF_UP); }

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
