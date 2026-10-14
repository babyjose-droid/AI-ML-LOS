package com.rhythm.los.tenant;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The tenant (lender) of the current request or job. Each tenant's data lives in its own PostgreSQL schema; the
 * default tenant uses the public schema. Every database connection is pointed at the current tenant's schema.
 */
public final class TenantContext {
    private TenantContext() {}

    public record Tenant(String code, String schema) {}

    public static final Tenant DEFAULT = new Tenant("default", "public");
    static final Pattern SAFE = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final ThreadLocal<Tenant> CURRENT = new ThreadLocal<>();

    public static Tenant get() { Tenant t = CURRENT.get(); return t == null ? DEFAULT : t; }
    public static String code() { return get().code(); }
    public static String schema() { return get().schema(); }
    public static boolean isDefault() { return DEFAULT.code().equals(code()); }

    public static void set(Tenant t) {
        if (t == null) { CURRENT.remove(); return; }
        if (!SAFE.matcher(t.schema()).matches()) throw new IllegalArgumentException("Bad schema");
        CURRENT.set(t);
    }

    public static void clear() { CURRENT.remove(); }

    /** Runs work as the given tenant, restoring the previous tenant afterwards. */
    public static <T> T call(Tenant t, Supplier<T> work) {
        Tenant before = CURRENT.get();
        set(t);
        try { return work.get(); } finally { if (before == null) CURRENT.remove(); else CURRENT.set(before); }
    }

    public static void run(Tenant t, Runnable work) { call(t, () -> { work.run(); return null; }); }
}
