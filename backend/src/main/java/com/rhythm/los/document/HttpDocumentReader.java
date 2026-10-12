package com.rhythm.los.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.integration.VendorException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.*;

/** Calls the Rhythm AI document service (ai/ in this repository). */
public class HttpDocumentReader implements DocumentReader {
    private final RestClient client;

    public HttpDocumentReader(String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(timeoutSeconds * 1000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override public String name() { return "AI-DOC"; }
    @Override public boolean extractsFields() { return true; }

    @Override
    public DocumentReading read(byte[] bytes, String contentType, String fileName, String declaredType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        String fname = fileName == null ? "document" : fileName;
        form.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return fname; }
        });
        if (declaredType != null) form.add("declared_type", declaredType);
        String body;
        try {
            body = client.post().uri("/v1/documents/analyze").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(withPartType(form, contentType)).retrieve().body(String.class);
        } catch (ResourceAccessException e) {
            throw new VendorException("AI document service unreachable: " + e.getMessage(), true);
        } catch (RestClientResponseException e) {
            throw new VendorException("AI document service returned " + e.getStatusCode().value(), e.getStatusCode().is5xxServerError());
        }
        return parse(body);
    }

    @Override public boolean readsStatements() { return true; }

    @Override
    public StatementReading analyzeStatement(byte[] bytes, String contentType, String fileName, String holderHint, Double declaredIncome) {
        LinkedMultiValueMap<String, Object> form = filePart(bytes, fileName);
        if (holderHint != null) form.add("holder_hint", holderHint);
        if (declaredIncome != null) form.add("declared_income", String.valueOf(declaredIncome));
        String body = post("/v1/statements/analyze", withPartType(form, contentType));
        return parseStatement(body);
    }

    @Override
    public byte[] unlockPdf(byte[] pdf, String password) {
        LinkedMultiValueMap<String, Object> form = filePart(pdf, "statement.pdf");
        form.add("password", password);
        try {
            String body = client.post().uri("/v1/pdf/unlock").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(withPartType(form, "application/pdf")).retrieve().body(String.class);
            return Base64.getDecoder().decode(Json.MAPPER.readTree(body).path("pdf").asText());
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 422)
                throw com.rhythm.los.common.ApiException.unprocessable("WRONG_PASSWORD", "The PDF password is not correct");
            throw com.rhythm.los.common.ApiException.unprocessable("UNLOCK_FAILED", "The PDF could not be unlocked");
        } catch (ResourceAccessException e) {
            throw com.rhythm.los.common.ApiException.unprocessable("UNLOCK_FAILED", "AI document service unreachable; try again shortly");
        } catch (com.rhythm.los.common.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw com.rhythm.los.common.ApiException.unprocessable("UNLOCK_FAILED", "The PDF could not be unlocked");
        }
    }

    private static LinkedMultiValueMap<String, Object> filePart(byte[] bytes, String fileName) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        String fname = fileName == null ? "document" : fileName;
        form.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return fname; }
        });
        return form;
    }

    private String post(String uri, Object form) {
        try {
            return client.post().uri(uri).contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class);
        } catch (ResourceAccessException e) {
            throw new VendorException("AI document service unreachable: " + e.getMessage(), true);
        } catch (RestClientResponseException e) {
            throw new VendorException("AI document service returned " + e.getStatusCode().value(), e.getStatusCode().is5xxServerError());
        }
    }

    /** Statement analysis -> a DocumentReading (header fields, status, tamper) plus the full analysis JSON for the screen and the engine. */
    public static StatementReading parseStatement(String body) {
        com.fasterxml.jackson.databind.node.ObjectNode n;
        try { n = (com.fasterxml.jackson.databind.node.ObjectNode) Json.MAPPER.readTree(body); }
        catch (Exception e) { throw new VendorException("Bad response from AI document service", false); }
        n.remove("unlockedPdf");
        Map<String, DocumentReading.FieldValue> fields = new LinkedHashMap<>();
        double conf = n.path("overallConfidence").asDouble();
        for (String k : List.of("accountHolder", "accountNumberMasked", "ifsc", "bank", "periodFrom", "periodTo", "openingBalance", "closingBalance")) {
            if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) fields.put(k, new DocumentReading.FieldValue(n.get(k).asText(), conf, n.path("engine").asText("ai")));
        }
        JsonNode s = n.path("summary");
        if (s.has("months")) fields.put("months", new DocumentReading.FieldValue(s.get("months").asText(), conf, "analysis"));
        @SuppressWarnings("unchecked") Map<String, Object> tamper = Json.MAPPER.convertValue(n.path("tamper"), Map.class);
        List<String> reasons = new ArrayList<>();
        n.path("reviewReasons").forEach(x -> reasons.add(x.asText()));
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("reconciliation", Json.MAPPER.convertValue(n.path("reconciliation"), Map.class));
        quality.put("issues", List.of());
        DocumentReading r = new DocumentReading(n.path("status").asText("NEEDS_REVIEW"), "BANK_STATEMENT", n.path("headerFound").asBoolean() ? 0.95 : 0.5,
                fields, List.of(), quality, tamper == null ? Map.of() : tamper, reasons, conf, n.path("engine").asText("ai"), false, null);
        return new StatementReading(r, n.toString());
    }

    private static Object withPartType(LinkedMultiValueMap<String, Object> form, String contentType) {
        // set the file part's content type so the AI service sees image/png, image/jpeg or application/pdf
        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.setContentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType));
        Object file = form.getFirst("file");
        form.set("file", new org.springframework.http.HttpEntity<>(file, h));
        return form;
    }

    static DocumentReading parse(String body) {
        JsonNode n;
        try { n = Json.MAPPER.readTree(body); } catch (Exception e) { throw new VendorException("Bad response from AI document service", false); }
        Map<String, DocumentReading.FieldValue> fields = new LinkedHashMap<>();
        n.path("fields").properties().forEach(e -> fields.put(e.getKey(), new DocumentReading.FieldValue(
                e.getValue().path("value").asText(), e.getValue().path("confidence").asDouble(), e.getValue().path("source").asText("ocr"))));
        List<Map<String, Object>> validations = Json.MAPPER.convertValue(n.path("validations"), Json.MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        @SuppressWarnings("unchecked") Map<String, Object> quality = Json.MAPPER.convertValue(n.path("quality"), Map.class);
        @SuppressWarnings("unchecked") Map<String, Object> tamper = Json.MAPPER.convertValue(n.path("tamper"), Map.class);
        List<String> reasons = new ArrayList<>();
        n.path("reviewReasons").forEach(x -> reasons.add(x.asText()));
        byte[] masked = n.hasNonNull("maskedImage") ? Base64.getDecoder().decode(n.get("maskedImage").asText()) : null;
        return new DocumentReading(n.path("status").asText("NEEDS_REVIEW"), n.path("documentType").asText("UNKNOWN"),
                n.path("typeConfidence").asDouble(), fields, validations == null ? List.of() : validations,
                quality == null ? Map.of() : quality, tamper == null ? Map.of() : tamper, reasons,
                n.path("overallConfidence").asDouble(), n.path("engine").asText("ai"), n.path("masked").asBoolean(false), masked);
    }
}
