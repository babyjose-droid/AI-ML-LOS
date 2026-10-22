package com.rhythm.los.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Sends push notifications to the mobile app through the Expo push service (APNs and FCM behind it). Returns the
 * tokens the service says are no longer registered, so they can be forgotten. Off with RHYTHM_PUSH_ENABLED=false.
 */
@Component
public class ExpoPush {
    private static final Logger log = LoggerFactory.getLogger(ExpoPush.class);
    public record Message(String to, String title, String body, Map<String, Object> data) {}

    private final boolean enabled;
    private final String url;
    private final String accessToken;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public ExpoPush(@Value("${rhythm.push.enabled:true}") boolean enabled, @Value("${rhythm.push.url:https://exp.host/--/api/v2/push/send}") String url,
                    @Value("${rhythm.push.access-token:}") String accessToken) {
        this.enabled = enabled;
        this.url = url;
        this.accessToken = accessToken;
    }

    public boolean enabled() { return enabled; }

    @SuppressWarnings("unchecked")
    public Set<String> send(List<Message> messages) {
        Set<String> gone = new HashSet<>();
        if (!enabled || messages.isEmpty()) return gone;
        for (int i = 0; i < messages.size(); i += 100) {
            List<Message> batch = messages.subList(i, Math.min(messages.size(), i + 100));
            try {
                List<Map<String, Object>> body = batch.stream().map(m -> {
                    Map<String, Object> x = new LinkedHashMap<>();
                    x.put("to", m.to()); x.put("title", m.title()); x.put("body", m.body()); x.put("data", m.data()); x.put("sound", "default");
                    return x;
                }).toList();
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                if (!accessToken.isBlank()) rb.header("Authorization", "Bearer " + accessToken);
                HttpResponse<String> res = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 300) { log.warn("push service answered {}", res.statusCode()); continue; }
                Object data = json.readValue(res.body(), Map.class).get("data");
                if (data instanceof List<?> tickets)
                    for (int j = 0; j < tickets.size() && j < batch.size(); j++)
                        if (tickets.get(j) instanceof Map<?, ?> t && "error".equals(t.get("status")) && t.get("details") instanceof Map<?, ?> d
                                && "DeviceNotRegistered".equals(d.get("error"))) gone.add(batch.get(j).to());
            } catch (Exception e) {
                log.warn("push not sent: {}", e.getMessage());
            }
        }
        return gone;
    }
}
