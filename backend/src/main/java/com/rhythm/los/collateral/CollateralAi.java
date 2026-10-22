package com.rhythm.los.collateral;

import com.rhythm.los.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

/**
 * Client for Collateral Document AI and Collateral Vision AI in the AI service. Empty when the service is not
 * configured or fails (retried 3 times): the case then goes to manual confirmation and never passes automatically (COL-E05).
 */
@Component
public class CollateralAi {
    private final RestClient client;
    private final boolean configured;

    public CollateralAi(@Value("${rhythm.document-ai.url:}") String url) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(120_000);
        this.configured = url != null && !url.isBlank();
        this.client = RestClient.builder().baseUrl(configured ? url : "http://localhost").requestFactory(f).build();
    }

    public boolean configured() { return configured; }

    public Optional<Map<String, Object>> document(byte[] bytes, String contentType, String fileName, String docType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", part(bytes, contentType, fileName));
        form.add("doc_type", docType);
        return post("/v1/collateral/documents/analyze", form);
    }

    /** Asset finance: reads a quotation, tax invoice, down-payment receipt or insurance policy. */
    public Optional<Map<String, Object>> assetDocument(byte[] bytes, String contentType, String fileName, String docType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", part(bytes, contentType, fileName));
        form.add("doc_type", docType);
        return post("/v1/assets/documents/analyze", form);
    }

    /** Trade finance: reads an invoice offered for financing (both GSTINs, due date, PO, amounts). */
    public Optional<Map<String, Object>> tradeInvoice(byte[] bytes, String contentType, String fileName) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", part(bytes, contentType, fileName));
        return post("/v1/trade/invoices/analyze", form);
    }

    /** Loan plans: admission and fee letters, fee demand, 7/12 land record, construction estimate, stage certificate, insurance. */
    public Optional<Map<String, Object>> planDocument(byte[] bytes, String contentType, String fileName, String docType) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", part(bytes, contentType, fileName));
        form.add("doc_type", docType);
        return post("/v1/plan/documents/analyze", form);
    }

    public Optional<Map<String, Object>> media(byte[] bytes, String contentType, String fileName) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", part(bytes, contentType, fileName));
        return post("/v1/collateral/media/analyze", form);
    }

    static HttpEntity<ByteArrayResource> part(byte[] bytes, String contentType, String fileName) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(contentType == null ? "application/octet-stream" : contentType));
        String name = fileName == null ? "file" : fileName;
        return new HttpEntity<>(new ByteArrayResource(bytes) { @Override public String getFilename() { return name; } }, h);
    }

    @SuppressWarnings("unchecked")
    Optional<Map<String, Object>> post(String path, LinkedMultiValueMap<String, Object> form) {
        if (!configured) return Optional.empty();
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String body = client.post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class);
                return Optional.of(Json.read(body, Map.class));
            } catch (RuntimeException e) {
                if (attempt == 3) return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
