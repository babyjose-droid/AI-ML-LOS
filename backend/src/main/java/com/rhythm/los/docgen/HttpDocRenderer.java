package com.rhythm.los.docgen;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PDF (WeasyPrint: proper Indian-language text) and Word import through the AI service. */
public class HttpDocRenderer implements DocRenderer {
    private final RestClient client;

    public HttpDocRenderer(String baseUrl, int timeoutSeconds) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(timeoutSeconds * 1000);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override public String name() { return "AI-DOCGEN"; }

    @Override
    public byte[] pdf(String html, String title) {
        try {
            return client.post().uri("/v1/docs/pdf").contentType(MediaType.APPLICATION_JSON).body(Json.write(Map.of("html", html, "title", title == null ? "" : title)))
                    .retrieve().body(byte[].class);
        } catch (ResourceAccessException e) {
            throw new VendorException("Document service unreachable: " + e.getMessage(), true);
        } catch (RestClientResponseException e) {
            throw new VendorException("Document service returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e.getStatusCode().is5xxServerError());
        }
    }

    @Override
    public WordImport word(byte[] docx) {
        LinkedMultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(docx) { @Override public String getFilename() { return "template.docx"; } });
        String body;
        try {
            body = client.post().uri("/v1/docs/docx-to-html").contentType(MediaType.MULTIPART_FORM_DATA).body(form).retrieve().body(String.class);
        } catch (ResourceAccessException e) {
            throw new VendorException("Document service unreachable: " + e.getMessage(), true);
        } catch (RestClientResponseException e) {
            throw new VendorException("The Word file could not be read (" + e.getStatusCode().value() + ")", false);
        }
        JsonNode n = Json.read(body, JsonNode.class);
        List<String> fields = new ArrayList<>(), msgs = new ArrayList<>();
        n.path("fields").forEach(x -> fields.add(x.asText()));
        n.path("messages").forEach(x -> msgs.add(x.asText()));
        return new WordImport(n.path("html").asText(""), fields, msgs);
    }
}
