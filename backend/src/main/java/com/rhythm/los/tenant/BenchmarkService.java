package com.rhythm.los.tenant;

import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.*;

/**
 * Anonymised benchmarking (FSD 19.5): each opted-in lender's metrics, compared with peers only when at least five
 * lenders are in the group. Lenders never see each other's names or values, only the quartiles and their own position.
 */
@Service
public class BenchmarkService {
    public record Metric(String key, String label, boolean higherIsBetter, String unit) {}

    public static final List<Metric> METRICS = List.of(
            new Metric("approvalRate", "Approval rate", true, "share"),
            new Metric("tatDays", "Days from application to disbursal (median)", false, "days"),
            new Metric("straightThrough", "Straight-through sanctions", true, "share"),
            new Metric("dpd30At6", "30+ days past due at 6 months on book", false, "share"),
            new Metric("fraudCatchRate", "Applications stopped for fraud", true, "share"),
            new Metric("yield", "Portfolio yield", true, "% a year"),
            new Metric("collectionEfficiency", "Book current (no days past due)", true, "share"));
    public static final int MIN_PEERS = 5;

    private final JdbcTemplate jdbc;
    private final TenantRegistry registry;
    private final boolean demo;

    public BenchmarkService(JdbcTemplate jdbc, TenantRegistry registry, @Value("${rhythm.demo-data:false}") boolean demo) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.demo = demo;
    }

    Double one(String sql, Object... args) {
        try { return jdbc.queryForObject(sql, Double.class, args); } catch (RuntimeException e) { return null; }
    }

    /** The current tenant's metrics, from its own schema (last 180 days for origination). */
    public Map<String, Double> compute() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("approvalRate", one("""
                select avg(case when d.decision like 'APPROVE%' then 1.0 else 0.0 end) from decision_record d
                 where d.id in (select max(id) from decision_record group by application_id) and d.created_at > now() - interval '180 days'"""));
        m.put("tatDays", one("""
                select percentile_cont(0.5) within group (order by extract(epoch from (s.created_at - a.created_at)) / 86400)
                  from disbursement s join loan_application a on a.id = s.application_id where s.status = 'SUCCESS' and s.created_at > now() - interval '180 days'"""));
        m.put("straightThrough", one("select avg(case when level = 'AUTO' then 1.0 else 0.0 end) from sanction_record where action = 'APPROVED' and created_at > now() - interval '180 days'"));
        m.put("dpd30At6", one("select avg(case when max_dpd > 30 then 1.0 else 0.0 end) from live_loan where disbursed_on <= (coalesce(last_feed_date, current_date) - interval '6 months')"));
        m.put("fraudCatchRate", one("select avg(case when fraud_state in ('REVIEW', 'BLOCK') then 1.0 else 0.0 end) from loan_application where created_at > now() - interval '180 days' and app_state <> 'DRAFT'"));
        m.put("yield", one("select sum(outstanding * rate) / nullif(sum(outstanding), 0) from live_loan where status = 'ACTIVE'"));
        m.put("collectionEfficiency", one("select sum(case when dpd = 0 then outstanding else 0 end) / nullif(sum(outstanding), 0) from live_loan where status = 'ACTIVE'"));
        return m;
    }

    /** Computes every opted-in tenant into the platform's anonymised table. */
    public int computeAll() {
        String period = YearMonth.now().toString();
        int n = 0;
        List<String> optedIn = TenantContext.call(TenantContext.DEFAULT, () -> jdbc.queryForList("select code from platform.tenant where benchmarking_opt_in and status <> 'SUSPENDED'", String.class));
        for (TenantRegistry.Entry e : registry.active()) {
            if (!optedIn.contains(e.code())) continue;
            Map<String, Double> m = TenantContext.call(e.tenant(), this::compute);
            TenantContext.run(TenantContext.DEFAULT, () -> m.forEach((k, v) -> jdbc.update("""
                    insert into platform.benchmark_metric (tenant_code, period, metric, value, source) values (?, ?, ?, ?, 'COMPUTED')
                    on conflict (tenant_code, period, metric) do update set value = excluded.value, computed_at = now()""", e.code(), period, k, v)));
            n++;
        }
        if (demo) seedSyntheticPeers(period);
        return n;
    }

    /** Demo only: five clearly labelled synthetic peers so the screen can be tried before five lenders have joined. */
    void seedSyntheticPeers(String period) {
        Random r = new Random(period.hashCode());
        TenantContext.run(TenantContext.DEFAULT, () -> {
            for (int i = 1; i <= 5; i++) {
                Map<String, Double> m = Map.of("approvalRate", 0.45 + r.nextDouble() * 0.3, "tatDays", 2 + r.nextDouble() * 8, "straightThrough", 0.05 + r.nextDouble() * 0.4,
                        "dpd30At6", 0.02 + r.nextDouble() * 0.08, "fraudCatchRate", 0.01 + r.nextDouble() * 0.05, "yield", 16 + r.nextDouble() * 8, "collectionEfficiency", 0.85 + r.nextDouble() * 0.12);
                String code = "synthetic-" + i;
                m.forEach((k, v) -> jdbc.update("""
                        insert into platform.benchmark_metric (tenant_code, period, metric, value, source) values (?, ?, ?, ?, 'SYNTHETIC')
                        on conflict (tenant_code, period, metric) do nothing""", code, period, k, v));
            }
        });
    }

    static double quantile(List<Double> sorted, double q) {
        if (sorted.isEmpty()) return Double.NaN;
        double pos = q * (sorted.size() - 1);
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        return sorted.get(lo) + (sorted.get(hi) - sorted.get(lo)) * (pos - lo);
    }

    /** 1 = best quartile. */
    public static int quartile(double mine, List<Double> all, boolean higherIsBetter) {
        long better = all.stream().filter(v -> higherIsBetter ? v > mine : v < mine).count();
        double rank = (double) better / all.size();
        return rank < 0.25 ? 1 : rank < 0.5 ? 2 : rank < 0.75 ? 3 : 4;
    }

    public Map<String, Object> view(CurrentUser u) {
        String me = TenantContext.code();
        Boolean opted = TenantContext.call(TenantContext.DEFAULT, () -> jdbc.queryForObject("select benchmarking_opt_in from platform.tenant where code = ?", Boolean.class, me));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("optedIn", Boolean.TRUE.equals(opted));
        out.put("minPeers", MIN_PEERS);
        Map<String, Double> mine = compute();
        String period = YearMonth.now().toString();
        List<Map<String, Object>> peers = TenantContext.call(TenantContext.DEFAULT, () -> jdbc.queryForList("""
                select b.tenant_code, b.metric, b.value, b.source from platform.benchmark_metric b
                 where b.period = ? and b.value is not null
                   and (b.source = 'SYNTHETIC' or b.tenant_code in (select code from platform.tenant where benchmarking_opt_in))""", period));
        boolean synthetic = peers.stream().anyMatch(p -> "SYNTHETIC".equals(p.get("source")));
        out.put("includesSyntheticPeers", synthetic);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Metric m : METRICS) {
            List<Double> vals = new ArrayList<>();
            Set<String> lenders = new HashSet<>();
            for (Map<String, Object> p : peers) {
                if (!m.key().equals(p.get("metric")) || me.equals(p.get("tenant_code"))) continue;
                vals.add(((Number) p.get("value")).doubleValue());
                lenders.add((String) p.get("tenant_code"));
            }
            Double v = mine.get(m.key());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("key", m.key());
            r.put("label", m.label());
            r.put("unit", m.unit());
            r.put("higherIsBetter", m.higherIsBetter());
            r.put("mine", v);
            int group = lenders.size() + (v == null ? 0 : 1);
            r.put("lenders", group);
            if (Boolean.TRUE.equals(opted) && group >= MIN_PEERS && v != null) {
                List<Double> all = new ArrayList<>(vals);
                all.add(v);
                Collections.sort(all);
                r.put("p25", quantile(all, 0.25));
                r.put("median", quantile(all, 0.5));
                r.put("p75", quantile(all, 0.75));
                r.put("quartile", quartile(v, all, m.higherIsBetter()));
            }
            rows.add(r);
        }
        out.put("metrics", rows);
        out.put("period", period);
        return out;
    }

    public Map<String, Object> refresh(CurrentUser u) {
        if (u.role() != com.rhythm.los.org.Role.ADMIN) throw ApiException.forbidden("ADMIN_ONLY", "An admin refreshes benchmarks");
        return Map.of("tenants", computeAll());
    }
}
