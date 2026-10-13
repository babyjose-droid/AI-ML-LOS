package com.rhythm.los.integration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every vendor call goes through here: one log row per attempt, exponential backoff on retryable
 * errors, and a DLQ row when all attempts fail. Nothing calls a vendor directly.
 */
@Service
public class IntegrationGateway {
    private static final Logger log = LoggerFactory.getLogger(IntegrationGateway.class);

    private final IntegrationLogRepository logs;
    private final int maxAttempts;
    private final long backoffMs;
    private ConnectorConfigRepository configs;
    private CircuitBreakers breakers;

    @org.springframework.beans.factory.annotation.Autowired
    void setConnectors(ConnectorConfigRepository configs, CircuitBreakers breakers) {
        this.configs = configs;
        this.breakers = breakers;
    }

    public IntegrationGateway(IntegrationLogRepository logs,
                              @Value("${rhythm.integration.max-attempts}") int maxAttempts,
                              @Value("${rhythm.integration.backoff-ms}") long backoffMs) {
        this.logs = logs;
        this.maxAttempts = maxAttempts;
        this.backoffMs = backoffMs;
    }

    public record Outcome<T>(boolean ok, T value, String error, Long lastLogId) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> Outcome<T> call(Long appId, String vendor, String operation, Supplier<T> fn, Function<T, String> summary, Long retryOf) {
        // the reference is the correlation ID: every attempt of this call carries it, and it goes in the server log
        String ref = operation + "-" + UUID.randomUUID().toString().substring(0, 8);
        String connector = CircuitBreakers.connectorOf(operation);
        ConnectorConfig cfg = configs == null ? null : configs.findById(connector).orElse(null);
        if (cfg != null && !cfg.isEnabled()) {
            IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, 1, "DISABLED", 0, ref, null,
                    "Connector " + connector + " is switched off in the integration hub", retryOf));
            return new Outcome<>(false, null, "Connector " + connector + " is switched off", row.getId());
        }
        if (breakers != null && !breakers.allow(connector)) {
            IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, 1, "CIRCUIT_OPEN", 0, ref, null,
                    "Circuit open after repeated failures; not called. Retry after the cool-down.", retryOf));
            return new Outcome<>(false, null, "The " + connector + " service is failing repeatedly; the call was not sent. Try again in a minute.", row.getId());
        }
        int attempts = cfg == null ? maxAttempts : Math.max(1, Math.min(cfg.getMaxAttempts(), 10));
        long timeoutMs = cfg == null ? Long.MAX_VALUE : cfg.getTimeoutMs();
        String lastError = null;
        Long lastId = null;
        boolean vendorSide = false;
        org.slf4j.MDC.put("correlationId", ref);
        try {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long t0 = System.nanoTime();
            try {
                T v = fn.get();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                String sum = summary.apply(v);
                if (ms > timeoutMs) sum = "Slow: " + ms + " ms, over the " + timeoutMs + " ms timeout · " + (sum == null ? "" : sum);
                IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, attempt, "SUCCESS", ms, ref,
                        sum, null, retryOf));
                if (breakers != null) breakers.success(connector);
                return new Outcome<>(true, v, null, row.getId());
            } catch (VendorException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                lastError = e.getMessage();
                vendorSide = e.isRetryable();
                boolean last = attempt == attempts || !e.isRetryable();
                IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, attempt, last ? "DLQ" : "FAILED", ms, ref,
                        null, e.getMessage(), retryOf));
                lastId = row.getId();
                log.warn("{} {} attempt {} failed: {}", vendor, operation, attempt, e.getMessage());
                if (last) break;
                sleep(backoffMs * (1L << (attempt - 1)));
            }
        }
        } finally {
            org.slf4j.MDC.remove("correlationId");
        }
        // only outages count towards opening the circuit; a business answer ("no record") is not a vendor failure
        if (breakers != null) {
            if (vendorSide) breakers.failure(connector, cfg == null ? 5 : cfg.getBreakerThreshold(), cfg == null ? 60 : cfg.getBreakerCooldownS());
            else breakers.success(connector);
        }
        return new Outcome<>(false, null, lastError, lastId);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
