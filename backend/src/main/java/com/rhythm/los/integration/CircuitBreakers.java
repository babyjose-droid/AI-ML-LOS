package com.rhythm.los.integration;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One circuit breaker per connector. Consecutive failed calls (after their retries, vendor-side errors only) open the
 * circuit: further calls fail at once without reaching the vendor until the cool-down passes; then one trial call is let
 * through (half-open) and its result closes or re-opens the circuit.
 */
@Component
public class CircuitBreakers {
    static final class State {
        int failures;
        Instant openUntil;
        boolean halfOpenTrial;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    public static String connectorOf(String operation) {
        int i = operation.indexOf('.');
        return i < 0 ? operation : operation.substring(0, i);
    }

    /** True when the call may go to the vendor. */
    public synchronized boolean allow(String connector) {
        State s = states.computeIfAbsent(connector, k -> new State());
        if (s.openUntil == null) return true;
        if (Instant.now().isBefore(s.openUntil)) return false;
        if (s.halfOpenTrial) return false;   // a trial call is already in flight
        s.halfOpenTrial = true;
        return true;
    }

    public synchronized void success(String connector) {
        State s = states.computeIfAbsent(connector, k -> new State());
        s.failures = 0;
        s.openUntil = null;
        s.halfOpenTrial = false;
    }

    public synchronized void failure(String connector, int threshold, int cooldownS) {
        State s = states.computeIfAbsent(connector, k -> new State());
        s.failures++;
        if (s.halfOpenTrial || (threshold > 0 && s.failures >= threshold)) {
            s.openUntil = Instant.now().plusSeconds(Math.max(1, cooldownS));
            s.halfOpenTrial = false;
        }
    }

    public synchronized void reset(String connector) { states.remove(connector); }

    public synchronized Map<String, Object> view(String connector) {
        State s = states.get(connector);
        Map<String, Object> m = new LinkedHashMap<>();
        boolean open = s != null && s.openUntil != null && Instant.now().isBefore(s.openUntil);
        m.put("state", s == null || s.openUntil == null ? "CLOSED" : open ? "OPEN" : "HALF_OPEN");
        m.put("consecutiveFailures", s == null ? 0 : s.failures);
        if (open) m.put("openUntil", s.openUntil.toString());
        return m;
    }
}
