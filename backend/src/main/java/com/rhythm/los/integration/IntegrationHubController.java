package com.rhythm.los.integration;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Integration hub (SCR-130): every connector with its settings, circuit-breaker state and the last 24 hours of calls;
 * settings changes by the admin; bulk retry of calls that did not reach a result.
 */
@RestController
public class IntegrationHubController {
    private final ConnectorConfigRepository configs;
    private final CircuitBreakers breakers;
    private final IntegrationLogRepository logs;
    private final DataFetchService data;
    private final AuditService audit;

    public IntegrationHubController(ConnectorConfigRepository configs, CircuitBreakers breakers, IntegrationLogRepository logs,
                                    DataFetchService data, AuditService audit) {
        this.configs = configs;
        this.breakers = breakers;
        this.logs = logs;
        this.data = data;
        this.audit = audit;
    }

    @GetMapping("/api/integrations/connectors")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','COMPLIANCE')")
    public List<Map<String, Object>> connectors() {
        Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
        List<IntegrationLog> recent = logs.findByCreatedAtAfterOrderByIdDesc(since);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectorConfig c : configs.findAll(org.springframework.data.domain.Sort.by("connector"))) {
            List<IntegrationLog> mine = recent.stream().filter(l -> CircuitBreakers.connectorOf(l.getOperation()).equals(c.getConnector())).toList();
            long ok = mine.stream().filter(l -> "SUCCESS".equals(l.getStatus())).count();
            long failed = mine.stream().filter(l -> !"SUCCESS".equals(l.getStatus()) && !"RESOLVED".equals(l.getStatus())).count();
            long pending = mine.stream().filter(l -> DataFetchService.RETRYABLE_STATUS.contains(l.getStatus())).count();
            double avg = mine.stream().filter(l -> "SUCCESS".equals(l.getStatus())).mapToLong(IntegrationLog::getLatencyMs).average().orElse(0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("config", c);
            m.put("breaker", breakers.view(c.getConnector()));
            m.put("calls24h", mine.size());
            m.put("success24h", ok);
            m.put("failed24h", failed);
            m.put("awaitingRetry", pending);
            m.put("avgLatencyMs", Math.round(avg));
            m.put("successRate", mine.isEmpty() ? null : Math.round(1000.0 * ok / mine.size()) / 1000.0);
            out.add(m);
        }
        return out;
    }

    public record ConnectorRequest(Boolean enabled, Integer timeoutMs, Integer maxAttempts, Integer breakerThreshold, Integer breakerCooldownS,
                                   String baseUrl, String notes) {}

    @PutMapping("/api/admin/connectors/{connector}")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ConnectorConfig update(@PathVariable String connector, @RequestBody ConnectorRequest r) {
        ConnectorConfig c = configs.findById(connector).orElseThrow(() -> ApiException.notFound("Connector " + connector));
        List<String> changes = new ArrayList<>();
        if (r.enabled() != null && r.enabled() != c.isEnabled()) { c.setEnabled(r.enabled()); changes.add(r.enabled() ? "switched on" : "switched off"); }
        if (r.timeoutMs() != null) { range("Timeout", r.timeoutMs(), 500, 300_000); changes.add("timeout " + c.getTimeoutMs() + " → " + r.timeoutMs() + " ms"); c.setTimeoutMs(r.timeoutMs()); }
        if (r.maxAttempts() != null) { range("Attempts", r.maxAttempts(), 1, 10); changes.add("attempts " + c.getMaxAttempts() + " → " + r.maxAttempts()); c.setMaxAttempts(r.maxAttempts()); }
        if (r.breakerThreshold() != null) { range("Breaker threshold", r.breakerThreshold(), 1, 100); changes.add("breaker threshold " + c.getBreakerThreshold() + " → " + r.breakerThreshold()); c.setBreakerThreshold(r.breakerThreshold()); }
        if (r.breakerCooldownS() != null) { range("Cool-down", r.breakerCooldownS(), 5, 3600); changes.add("cool-down " + c.getBreakerCooldownS() + " → " + r.breakerCooldownS() + " s"); c.setBreakerCooldownS(r.breakerCooldownS()); }
        if (r.baseUrl() != null) {
            String u = r.baseUrl().trim();
            if (!u.isEmpty() && !u.matches("^https://[A-Za-z0-9.-]+(:[0-9]{2,5})?(/[A-Za-z0-9._~/-]*)?$"))
                throw ApiException.unprocessable("BAD_URL", "Base URL must be an https:// address");
            c.setBaseUrl(u.isEmpty() ? null : u);
            changes.add("base URL set");
        }
        if (r.notes() != null) c.setNotes(r.notes().length() > 500 ? r.notes().substring(0, 500) : r.notes());
        c.setUpdatedBy(CurrentUser.get().username());
        c.setUpdatedAt(Instant.now());
        configs.save(c);
        audit.record(CurrentUser.get(), "integration.connector_updated", "CONNECTOR", connector, null, changes.isEmpty() ? "notes" : String.join("; ", changes));
        return c;
    }

    @PostMapping("/api/admin/connectors/{connector}/reset-breaker")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> resetBreaker(@PathVariable String connector) {
        if (!configs.existsById(connector)) throw ApiException.notFound("Connector " + connector);
        breakers.reset(connector);
        audit.record(CurrentUser.get(), "integration.breaker_reset", "CONNECTOR", connector, null, "Circuit closed by hand");
        return breakers.view(connector);
    }

    public record BulkRetry(List<Long> ids) {}

    @PostMapping("/api/integrations/logs/retry-bulk")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS')")
    public List<Map<String, Object>> bulkRetry(@RequestBody BulkRetry r) {
        if (r.ids() == null || r.ids().isEmpty()) throw ApiException.unprocessable("NOTHING_SELECTED", "Select at least one call to retry");
        if (r.ids().size() > 50) throw ApiException.unprocessable("TOO_MANY", "Retry at most 50 calls at a time");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(r.ids())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            try {
                var a = data.retry(id, CurrentUser.get());
                m.put("ok", true);
                m.put("dataState", a.getDataState());
            } catch (ApiException e) {
                m.put("ok", false);
                m.put("error", e.getMessage());
            }
            out.add(m);
        }
        return out;
    }

    static void range(String what, int v, int min, int max) {
        if (v < min || v > max) throw ApiException.unprocessable("OUT_OF_RANGE", what + " must be between " + min + " and " + max);
    }
}
