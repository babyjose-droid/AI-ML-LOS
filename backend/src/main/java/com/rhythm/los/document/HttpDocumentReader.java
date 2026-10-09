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
