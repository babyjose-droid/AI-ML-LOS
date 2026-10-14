package com.rhythm.los.assistant;

import com.rhythm.los.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Optional generative answer through the AI service (Claude, when it has an API key). Only masked text is sent:
 * the question with names and identifiers removed, and policy passages. Any failure falls back to the extractive answer.
 */
@Component
public class AssistantLlm {
    private final RestClient client;
    private final boolean configured;

    public AssistantLlm(@Value("${rhythm.document-ai.url:}") String url) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(45_000);
        this.configured = url != null && !url.isBlank();
        this.client = RestClient.builder().baseUrl(configured ? url : "http://localhost").requestFactory(f).build();
    }

    @SuppressWarnings("unchecked")
    public Optional<String> answer(String question, List<Map<String, String>> passages) {
        if (!configured) return Optional.empty();
        try {
            String body = client.post().uri("/v1/assistant/answer").contentType(MediaType.APPLICATION_JSON)
                    .body(Json.write(Map.of("question", question, "passages", passages))).retrieve().body(String.class);
            Object a = Json.read(body, Map.class).get("answer");
            return a instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
