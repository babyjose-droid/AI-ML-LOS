package com.rhythm.los.model;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

@Component
public class HttpModelTrainer implements ModelTrainer {
    private final RestClient client;
    private final boolean configured;

    public HttpModelTrainer(@Value("${rhythm.document-ai.url:}") String url) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(5000);
        f.setReadTimeout(300_000);
        this.configured = url != null && !url.isBlank();
        this.client = RestClient.builder().baseUrl(configured ? url : "http://localhost").requestFactory(f).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> train(Map<String, Object> request) {
        if (!configured) throw ApiException.conflict("TRAINER_UNAVAILABLE", "Model training needs the AI service (DOCUMENT_AI_URL)");
        try {
            String body = client.post().uri("/v1/models/train").contentType(MediaType.APPLICATION_JSON).body(Json.write(request)).retrieve().body(String.class);
            return Json.read(body, Map.class);
        } catch (RestClientResponseException e) {
            String msg = e.getResponseBodyAsString();
            throw ApiException.unprocessable("TRAINING_FAILED", "Training failed: " + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        } catch (ResourceAccessException e) {
            throw ApiException.conflict("TRAINER_UNAVAILABLE", "AI service unreachable: " + e.getMessage());
        }
    }
}
