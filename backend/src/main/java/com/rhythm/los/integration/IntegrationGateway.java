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
        String ref = operation + "-" + UUID.randomUUID().toString().substring(0, 8);
        String lastError = null;
        Long lastId = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long t0 = System.nanoTime();
            try {
                T v = fn.get();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, attempt, "SUCCESS", ms, ref,
                        summary.apply(v), null, retryOf));
                return new Outcome<>(true, v, null, row.getId());
            } catch (VendorException e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                lastError = e.getMessage();
                boolean last = attempt == maxAttempts || !e.isRetryable();
                IntegrationLog row = logs.save(new IntegrationLog(appId, vendor, operation, attempt, last ? "DLQ" : "FAILED", ms, ref,
                        null, e.getMessage(), retryOf));
                lastId = row.getId();
                log.warn("{} {} attempt {} failed: {}", vendor, operation, attempt, e.getMessage());
                if (last) break;
                sleep(backoffMs * (1L << (attempt - 1)));
            }
        }
        return new Outcome<>(false, null, lastError, lastId);
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
