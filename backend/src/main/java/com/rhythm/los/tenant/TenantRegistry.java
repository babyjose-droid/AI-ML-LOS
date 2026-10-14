package com.rhythm.los.tenant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reads the platform's tenant registry (schema "platform"), cached for a few seconds. */
@Component
public class TenantRegistry {
    public record Entry(String code, String name, String schema, String status) {
        public TenantContext.Tenant tenant() { return new TenantContext.Tenant(code, schema); }
    }

    private final JdbcTemplate jdbc;
    private volatile Map<String, Entry> cache = new ConcurrentHashMap<>();
    private volatile long loadedAt;

    public TenantRegistry(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public synchronized void reload() {
        Map<String, Entry> m = new ConcurrentHashMap<>();
        TenantContext.run(TenantContext.DEFAULT, () -> jdbc.query("select code, name, schema_name, status from platform.tenant", rs -> {
            m.put(rs.getString(1), new Entry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }));
        cache = m;
        loadedAt = System.currentTimeMillis();
    }

    public Optional<Entry> find(String code) {
        if (code == null) return Optional.empty();
        if (System.currentTimeMillis() - loadedAt > 10_000 || !cache.containsKey(code)) {
            try { reload(); } catch (RuntimeException e) { if (cache.isEmpty()) return "default".equals(code) ? Optional.of(new Entry("default", "Default", "public", "LIVE")) : Optional.empty(); }
        }
        return Optional.ofNullable(cache.get(code));
    }

    /** Tenants whose jobs run: live and onboarding (not suspended). */
    public List<Entry> active() {
        try { reload(); } catch (RuntimeException e) { return List.of(new Entry("default", "Default", "public", "LIVE")); }
        return cache.values().stream().filter(e -> !"SUSPENDED".equals(e.status())).sorted(Comparator.comparing(Entry::code)).toList();
    }

    public List<Entry> all() {
        reload();
        return cache.values().stream().sorted(Comparator.comparing(Entry::code)).toList();
    }
}
