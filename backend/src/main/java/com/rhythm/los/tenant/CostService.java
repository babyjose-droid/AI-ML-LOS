package com.rhythm.los.tenant;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Cost per loan file: vendor calls (per successful call) and staff handling (per workflow stage completed), at rates the
 * lender sets. Reported per disbursed file two ways: the direct cost of the files that were disbursed, and the all-in
 * cost that also carries the files rejected or withdrawn along the way.
 */
@Service
public class CostService {
    static final Set<Role> EDITORS = EnumSet.of(Role.ADMIN, Role.CRO);

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public CostService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public List<Map<String, Object>> rates() {
        return jdbc.queryForList("select item, label, kind, unit_cost as \"unitCost\", updated_by as \"updatedBy\", updated_at as \"updatedAt\" from cost_rate order by kind desc, item");
    }

    public record RateChange(String item, Double unitCost) {}

    @Transactional
    public List<Map<String, Object>> updateRates(List<RateChange> changes, CurrentUser by) {
        if (!EDITORS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "An admin or the CRO sets cost rates");
        if (changes == null || changes.isEmpty()) throw ApiException.unprocessable("EMPTY", "No rates to change");
        for (RateChange c : changes) {
            if (c.unitCost() == null || c.unitCost() < 0 || c.unitCost() > 1_00_000) throw ApiException.unprocessable("BAD_RATE", "A rate must be between ₹0 and ₹1,00,000");
            int n = jdbc.update("update cost_rate set unit_cost = ?, updated_by = ?, updated_at = now() where item = ?", c.unitCost(), by.username(), c.item());
            if (n == 0) throw ApiException.notFound("Cost item " + c.item());
            audit.record(by, "cost.rate_changed", "COST", c.item(), null, "₹" + c.unitCost());
        }
        return rates();
    }

    /** Cost per file over the last {@code days} days, overall, by product and by item. */
    public Map<String, Object> perFile(int days) {
        int d = Math.max(7, Math.min(730, days));
        String apps = "select id, product_code, app_state from loan_application where created_at > now() - make_interval(days => " + d + ") and app_state <> 'DRAFT'";
        List<Map<String, Object>> perApp = jdbc.queryForList("""
                with apps as (%s),
                v as (select l.application_id id, sum(r.unit_cost) c from integration_log l join cost_rate r on r.item = l.operation
                       where l.status = 'SUCCESS' and l.application_id in (select id from apps) group by l.application_id),
                s as (select st.application_id id, sum(r.unit_cost) c from app_stage st join cost_rate r on r.item = 'stage:' || st.stage_key
                       where st.status = 'DONE' and st.application_id in (select id from apps) group by st.application_id)
                select a.id, a.product_code, a.app_state, coalesce(v.c, 0) vendor, coalesce(s.c, 0) staff
                  from apps a left join v on v.id = a.id left join s on s.id = a.id""".formatted(apps));
        Map<String, double[]> byProduct = new TreeMap<>();   // vendor, staff, disbursed count, all files cost
        double vendorAll = 0, staffAll = 0, vendorDisb = 0, staffDisb = 0;
        int files = perApp.size(), disbursed = 0;
        for (Map<String, Object> x : perApp) {
            double v = ((Number) x.get("vendor")).doubleValue(), s = ((Number) x.get("staff")).doubleValue();
            boolean disb = "DISBURSED".equals(x.get("app_state"));
            String p = String.valueOf(x.get("product_code"));
            double[] b = byProduct.computeIfAbsent(p, k -> new double[5]);
            b[3] += v + s;
            b[4]++;
            vendorAll += v;
            staffAll += s;
            if (disb) { disbursed++; vendorDisb += v; staffDisb += s; b[0] += v; b[1] += s; b[2]++; }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", d);
        m.put("files", files);
        m.put("disbursed", disbursed);
        m.put("directPerDisbursed", disbursed == 0 ? null : Math.round((vendorDisb + staffDisb) / disbursed));
        m.put("vendorPerDisbursed", disbursed == 0 ? null : Math.round(vendorDisb / disbursed));
        m.put("staffPerDisbursed", disbursed == 0 ? null : Math.round(staffDisb / disbursed));
        m.put("allInPerDisbursed", disbursed == 0 ? null : Math.round((vendorAll + staffAll) / disbursed));
        m.put("perFileStarted", files == 0 ? null : Math.round((vendorAll + staffAll) / files));
        List<Map<String, Object>> products = new ArrayList<>();
        byProduct.forEach((p, b) -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("product", p);
            r.put("files", (int) b[4]);
            r.put("disbursed", (int) b[2]);
            r.put("directPerDisbursed", b[2] == 0 ? null : Math.round((b[0] + b[1]) / b[2]));
            r.put("allInPerDisbursed", b[2] == 0 ? null : Math.round(b[3] / b[2]));
            products.add(r);
        });
        m.put("byProduct", products);
        m.put("byItem", jdbc.queryForList("""
                with apps as (%s)
                select r.item, r.label, r.kind, count(*) as uses, sum(r.unit_cost) as cost from (
                    select l.operation item from integration_log l where l.status = 'SUCCESS' and l.application_id in (select id from apps)
                    union all
                    select 'stage:' || st.stage_key from app_stage st where st.status = 'DONE' and st.application_id in (select id from apps)) u
                join cost_rate r on r.item = u.item group by r.item, r.label, r.kind order by cost desc""".formatted(apps)));
        return m;
    }
}
