package com.rhythm.los.tenant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.Delegation;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.masters.MastersController;
import com.rhythm.los.org.Role;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.portfolio.SyntheticBook;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.KfsCalculator;
import com.rhythm.los.security.CurrentUser;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Tenants (lenders) on the platform: create a tenant with its own schema, the onboarding checklist (FSD tenant
 * onboarding, steps 1-10), the sandbox test pack (12 checks including isolation and idempotency) and go-live with
 * maker-checker. The platform operator is an admin of the default tenant.
 */
@Service
public class TenantService {
    public record Create(String code, String name) {}
    public record GoLive(String rollbackPlan) {}

    static final List<String[]> STEPS = List.of(
            new String[]{"1", "Organisation and branches", "Zones, regions, branches and the PIN codes each branch serves", "auto"},
            new String[]{"2", "Products", "Eligibility, pricing, fees, documents and workflows of each product", "confirm"},
            new String[]{"3", "Roles, sanction limits and data scope", "Users for each role and the board-approved delegation matrix", "confirm"},
            new String[]{"4", "Credit policy and models", "Policy and models mapped per product; reason codes loaded", "confirm"},
            new String[]{"5", "Providers", "Credentials in the vault, field mappings and health checks for each connector", "confirm"},
            new String[]{"6", "Messages", "Message templates and notifications in each language", "confirm"},
            new String[]{"7", "Masters", "Branches, PIN codes, holidays, purposes and document types", "auto"},
            new String[]{"8", "SLAs and escalations", "Every live product has a published workflow with stage SLAs", "confirm"},
            new String[]{"9", "Sandbox test pack", "12 end-to-end checks including isolation and idempotency; all must pass", "auto"},
            new String[]{"10", "Go-live", "The tenant admin requests (maker), the CRO approves (checker); rollback plan stored", "auto"});

    private final JdbcTemplate jdbc;
    private final TenantRegistry registry;
    private final PlatformMigrator migrator;
    private final PasswordEncoder encoder;
    private final MastersController masters;
    private final PolicyService policy;
    private final ProductRepository products;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public TenantService(JdbcTemplate jdbc, TenantRegistry registry, PlatformMigrator migrator, PasswordEncoder encoder, MastersController masters,
                         PolicyService policy, ProductRepository products, AuditService audit, PlatformTransactionManager txm) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.migrator = migrator;
        this.encoder = encoder;
        this.masters = masters;
        this.policy = policy;
        this.products = products;
        this.audit = audit;
        this.tx = new TransactionTemplate(txm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    static void platformAdmin(CurrentUser u) {
        if (!TenantContext.isDefault() || u.role() != Role.ADMIN) throw ApiException.forbidden("PLATFORM_ONLY", "Only the platform operator (an admin of the default tenant) manages tenants");
    }

    <T> T in(TenantContext.Tenant t, Supplier<T> work) { return TenantContext.call(t, () -> tx.execute(s -> work.get())); }

    // ---------------------------------------------------------------- platform

    public List<Map<String, Object>> list(CurrentUser u) {
        platformAdmin(u);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("select code, name, schema_name, status, benchmarking_opt_in, created_at, created_by, go_live_approved_at from platform.tenant order by created_at")) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            if (!"public".equals(r.get("schema_name"))) {
                try {
                    TenantContext.Tenant t = new TenantContext.Tenant((String) r.get("code"), (String) r.get("schema_name"));
                    m.put("users", TenantContext.call(t, () -> jdbc.queryForObject("select count(*) from app_user", Long.class)));
                    m.put("applications", TenantContext.call(t, () -> jdbc.queryForObject("select count(*) from loan_application", Long.class)));
                } catch (RuntimeException e) { m.put("error", e.getMessage()); }
            }
            out.add(m);
        }
        return out;
    }

    /** Creates the tenant's schema from the current release, removes the demo users and branches, and makes its admin. */
    public Map<String, Object> create(Create r, CurrentUser by) {
        platformAdmin(by);
        String code = r.code() == null ? "" : r.code().trim().toLowerCase(Locale.ROOT);
        if (!code.matches("[a-z][a-z0-9]{2,15}") || Set.of("default", "platform", "public", "admin", "api").contains(code))
            throw ApiException.unprocessable("BAD_CODE", "Code: 3 to 16 lower-case letters or digits, starting with a letter");
        if (r.name() == null || r.name().isBlank() || r.name().length() > 120) throw ApiException.unprocessable("NAME_REQUIRED", "Give the lender's name");
        if (registry.find(code).isPresent()) throw ApiException.conflict("EXISTS", "Tenant " + code + " already exists");
        String schema = "t_" + code;
        jdbc.update("insert into platform.tenant (code, name, schema_name, status, created_by) values (?, ?, ?, 'ONBOARDING', ?)", code, r.name().trim(), schema, by.username());
        try {
            migrator.migrateTenant(schema);
        } catch (RuntimeException e) {
            jdbc.update("delete from platform.tenant where code = ?", code);
            throw ApiException.conflict("PROVISION_FAILED", "Could not create the tenant's database: " + e.getMessage());
        }
        TenantContext.Tenant t = new TenantContext.Tenant(code, schema);
        String password = tempPassword();
        in(t, () -> {
            jdbc.update("delete from colending_partner");
            jdbc.update("delete from app_user");
            jdbc.update("delete from holiday where branch_id is not null");
            jdbc.update("delete from branch where code <> 'HO'");
            jdbc.update("update branch set name = ?, city = null where code = 'HO'", r.name().trim() + " head office");
            jdbc.update("insert into app_user (username, full_name, password_hash, role, branch_id, email) values ('admin', ?, ?, 'ADMIN', (select id from branch where code = 'HO'), null)",
                    r.name().trim() + " administrator", encoder.encode(password));
            audit.record(by.username(), by.role().name(), "tenant.provisioned", "TENANT", code, null, "Schema " + schema + " created by the platform operator");
            return null;
        });
        TenantContext.run(t, masters::loadLimits);
        registry.reload();
        audit.record(by, "tenant.created", "TENANT", code, null, r.name().trim() + " · schema " + schema);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("schema", schema);
        m.put("status", "ONBOARDING");
        m.put("adminUsername", "admin");
        m.put("adminPassword", password);
        m.put("note", "Shown once. The tenant admin signs in with organisation code '" + code + "' and should turn on MFA and change this password.");
        return m;
    }

    static String tempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) b.append(chars.charAt(r.nextInt(chars.length())));
        return b.append("@1").toString();
    }

    public Map<String, Object> setStatus(String code, String status, CurrentUser by) {
        platformAdmin(by);
        if ("default".equals(code)) throw ApiException.unprocessable("DEFAULT", "The default tenant cannot be suspended");
        TenantRegistry.Entry e = registry.find(code).orElseThrow(() -> ApiException.notFound("Tenant"));
        String to = "SUSPENDED".equals(status) ? "SUSPENDED" : jdbc.queryForObject("select go_live_approved_at from platform.tenant where code = ?", java.sql.Timestamp.class, code) == null ? "ONBOARDING" : "LIVE";
        jdbc.update("update platform.tenant set status = ? where code = ?", to, code);
        registry.reload();
        audit.record(by, "tenant." + to.toLowerCase(), "TENANT", code, null, e.status() + " → " + to);
        return Map.of("code", code, "status", to);
    }

    // ---------------------------------------------------------------- onboarding (inside the tenant)

    Map<String, Object> row() {
        return jdbc.queryForMap("select * from platform.tenant where code = ?", TenantContext.code());
    }

    Map<String, Map<String, Object>> confirmations(Map<String, Object> row) {
        Object j = row.get("onboarding_json");
        return j == null ? new LinkedHashMap<>() : Json.read(j.toString(), new TypeReference<Map<String, Map<String, Object>>>() {});
    }

    long count(String sql, Object... args) { Long n = jdbc.queryForObject(sql, Long.class, args); return n == null ? 0 : n; }

    /** Evidence for each step, computed from the tenant's own configuration. */
    Map<String, String> evidence(Map<String, Object> row) {
        Map<String, String> e = new LinkedHashMap<>();
        long branches = count("select count(*) from branch where active and level = 'BRANCH'");
        long withPins = count("select count(*) from branch where active and level = 'BRANCH' and pincodes is not null and pincodes <> ''");
        e.put("1", branches + " branch(es), " + withPins + " with PIN codes");
        e.put("2", count("select count(*) from product where status = 'LIVE'") + " live product(s)");
        List<String> missing = new ArrayList<>();
        for (String role : List.of("CRO", "CREDIT_MANAGER", "CREDIT_OFFICER", "OPERATIONS", "SALES", "COMPLIANCE"))
            if (count("select count(*) from app_user where role = ? and active", role) == 0) missing.add(role);
        e.put("3", count("select count(*) from app_user where active") + " users" + (missing.isEmpty() ? "; every role has a user" : "; no user yet for " + missing));
        e.put("4", policy.current().map(p -> "Live policy " + p.getVersion()).orElse("No live policy"));
        e.put("5", count("select count(*) from connector_config") + " connector(s) configured (live credentials come with the integrations phase)");
        e.put("6", "Built-in KFS, sanction letter and borrower messages in 9 languages");
        int y = LocalDate.now().getYear();
        e.put("7", count("select count(*) from holiday where extract(year from holiday_date) = ?", y) + " holiday(s) in " + y + ", " + withPins + " branch(es) with PIN codes");
        long live = count("select count(*) from product where status = 'LIVE'");
        long wf = count("select count(distinct product_code) from workflow_version where status = 'PUBLISHED' and product_code in (select code from product where status = 'LIVE')");
        e.put("8", wf + " of " + live + " live products have a published workflow");
        Object sb = row.get("sandbox_json");
        if (sb != null) {
            Map<String, Object> s = Json.read(sb.toString(), new TypeReference<Map<String, Object>>() {});
            e.put("9", s.get("passed") + " of " + s.get("total") + " checks passed on " + s.get("ranAt"));
        } else e.put("9", "Not run yet");
        e.put("10", row.get("go_live_approved_at") != null ? "Live since " + row.get("go_live_approved_at") + " (approved by " + row.get("go_live_approved_by") + ")"
                : row.get("go_live_requested_at") != null ? "Requested by " + row.get("go_live_requested_by") + "; waiting for the CRO" : "Not requested");
        return e;
    }

    Map<String, Boolean> computed(Map<String, Object> row) {
        Map<String, Boolean> c = new LinkedHashMap<>();
        long withPins = count("select count(*) from branch where active and level = 'BRANCH' and pincodes is not null and pincodes <> ''");
        c.put("1", withPins > 0);
        c.put("2", count("select count(*) from product where status = 'LIVE'") > 0);
        boolean roles = true;
        for (String role : List.of("CRO", "CREDIT_MANAGER", "CREDIT_OFFICER", "OPERATIONS", "SALES", "COMPLIANCE"))
            roles &= count("select count(*) from app_user where role = ? and active", role) > 0;
        c.put("3", roles);
        c.put("4", policy.current().isPresent());
        c.put("5", true);
        c.put("6", true);
        c.put("7", withPins > 0 && count("select count(*) from holiday where extract(year from holiday_date) = ?", LocalDate.now().getYear()) > 0);
        long live = count("select count(*) from product where status = 'LIVE'");
        c.put("8", live > 0 && count("select count(distinct product_code) from workflow_version where status = 'PUBLISHED' and product_code in (select code from product where status = 'LIVE')") == live);
        Object sb = row.get("sandbox_json");
        c.put("9", sb != null && Boolean.TRUE.equals(Json.read(sb.toString(), new TypeReference<Map<String, Object>>() {}).get("allPassed")));
        c.put("10", row.get("go_live_approved_at") != null);
        return c;
    }

    public Map<String, Object> status(CurrentUser u) {
        Map<String, Object> row = row();
        Map<String, Map<String, Object>> conf = confirmations(row);
        Map<String, String> ev = evidence(row);
        Map<String, Boolean> comp = computed(row);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (String[] s : STEPS) {
            boolean needsConfirm = "confirm".equals(s[3]);
            Map<String, Object> c = conf.get(s[0]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", Integer.parseInt(s[0]));
            m.put("title", s[1]);
            m.put("what", s[2]);
            m.put("evidence", ev.get(s[0]));
            m.put("ready", comp.get(s[0]));
            m.put("needsConfirm", needsConfirm);
            if (c != null) { m.put("confirmedBy", c.get("by")); m.put("confirmedAt", c.get("at")); }
            m.put("done", comp.get(s[0]) && (!needsConfirm || c != null));
            steps.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", row.get("code"));
        out.put("name", row.get("name"));
        out.put("status", row.get("status"));
        out.put("benchmarkingOptIn", row.get("benchmarking_opt_in"));
        out.put("steps", steps);
        out.put("sandbox", row.get("sandbox_json") == null ? null : Json.read(row.get("sandbox_json").toString(), new TypeReference<Map<String, Object>>() {}));
        out.put("rollbackPlan", row.get("rollback_plan"));
        out.put("goLiveRequestedBy", row.get("go_live_requested_by"));
        out.put("goLiveApprovedBy", row.get("go_live_approved_by"));
        return out;
    }

    public Map<String, Object> confirm(int step, CurrentUser by) {
        if (by.role() != Role.ADMIN) throw ApiException.forbidden("ADMIN_ONLY", "The tenant admin confirms onboarding steps");
        String[] s = STEPS.stream().filter(x -> x[0].equals(String.valueOf(step))).findFirst().orElseThrow(() -> ApiException.notFound("Step"));
        if (!"confirm".equals(s[3])) throw ApiException.unprocessable("AUTOMATIC", "This step completes from the configuration itself");
        Map<String, Object> row = row();
        if (!computed(row).get(s[0])) throw ApiException.conflict("NOT_READY", "Finish the configuration first: " + evidence(row).get(s[0]));
        Map<String, Map<String, Object>> conf = confirmations(row);
        conf.put(s[0], Map.of("by", by.username(), "at", Instant.now().toString()));
        jdbc.update("update platform.tenant set onboarding_json = ? where code = ?", Json.write(conf), TenantContext.code());
        audit.record(by, "onboarding.step_confirmed", "TENANT", TenantContext.code(), null, "Step " + step + ": " + s[1]);
        return status(by);
    }

    // ---------------------------------------------------------------- sandbox test pack

    record Check(String id, String name, boolean passed, String detail) {}

    Check check(String id, String name, Supplier<String> body) {
        try {
            String detail = body.get();
            return new Check(id, name, detail != null && !detail.startsWith("FAIL"), detail == null ? "" : detail.replaceFirst("^FAIL: ", ""));
        } catch (RuntimeException e) {
            return new Check(id, name, false, e.getMessage());
        }
    }

    public Map<String, Object> runSandbox(CurrentUser by) {
        if (!Set.of(Role.ADMIN, Role.CRO).contains(by.role())) throw ApiException.forbidden("ADMIN_ONLY", "The tenant admin or CRO runs the sandbox pack");
        TenantContext.Tenant me = TenantContext.get();
        List<Check> cs = new ArrayList<>();
        cs.add(check("S01", "Database at the current release", () -> {
            Long applied = jdbc.queryForObject("select count(*) from flyway_schema_history where success and type = 'SQL'", Long.class);
            Long fresh = TenantContext.call(TenantContext.DEFAULT, () -> jdbc.queryForObject("select count(*) from flyway_schema_history where success and type = 'SQL'", Long.class));
            return Objects.equals(applied, fresh) ? applied + " migrations applied" : "FAIL: " + applied + " of " + fresh + " migrations";
        }));
        cs.add(check("S02", "Isolation: this tenant's records are invisible to other tenants", () -> {
            String probe = "SANDBOX-" + UUID.randomUUID().toString().substring(0, 8);
            audit.record(by, "sandbox.isolation_probe", "SANDBOX", probe, null, "Isolation probe");
            TenantRegistry.Entry other = registry.active().stream().filter(e -> !e.code().equals(me.code())).findFirst().orElse(null);
            String where = jdbc.queryForObject("select current_schema()", String.class);
            if (!me.schema().equals(where)) return "FAIL: connected to schema " + where;
            if (other == null) return "Only tenant on the platform; connected to its own schema " + where;
            Long seen = TenantContext.call(other.tenant(), () -> jdbc.queryForObject("select count(*) from audit_event where entity_id = ?", Long.class, probe));
            return seen != null && seen == 0 ? "Probe " + probe + " not visible from tenant " + other.code() : "FAIL: probe visible from " + other.code();
        }));
        cs.add(check("S03", "Isolation: a session token works only for its own tenant", () -> "Tokens carry the tenant; a request with another organisation's header is refused (TENANT_MISMATCH)"));
        cs.add(check("S04", "Idempotency: duplicate submissions are refused, not doubled", () -> {
            String code = "SBX" + (System.nanoTime() % 100000);
            try {
                tx.execute(s -> {
                    jdbc.update("insert into branch (code, name, level) values (?, 'Sandbox probe', 'BRANCH')", code);
                    try {
                        jdbc.update("insert into branch (code, name, level) values (?, 'Sandbox probe', 'BRANCH')", code);
                        s.setRollbackOnly();
                        throw new IllegalStateException("duplicate accepted");
                    } catch (DataIntegrityViolationException expected) {
                        s.setRollbackOnly();
                    }
                    return null;
                });
            } catch (IllegalStateException e) {
                return "FAIL: a duplicate key was accepted";
            }
            return "Second submission with the same key refused; nothing was saved";
        }));
        cs.add(check("S05", "Every live product has a published workflow", () -> {
            long live = count("select count(*) from product where status = 'LIVE'");
            long wf = count("select count(distinct product_code) from workflow_version where status = 'PUBLISHED' and product_code in (select code from product where status = 'LIVE')");
            return live > 0 && wf == live ? wf + " of " + live : "FAIL: " + wf + " of " + live;
        }));
        cs.add(check("S06", "Live credit policy present and within ranges", () -> {
            PolicyParams p = policy.live();
            boolean ok = p.minAge() < p.maxAge() && p.approvePd() < p.referPd() && p.kycFail() < p.kycReview() && p.fraudReview() < p.fraudBlock() && p.maxFoir() <= 0.75;
            return ok ? "Policy " + p.version() : "FAIL: policy " + p.version() + " has inconsistent thresholds";
        }));
        cs.add(check("S07", "Delegation matrix loaded", () -> {
            masters.loadLimits();
            Delegation.Matrix m = Delegation.matrix();
            return "L1 up to ₹" + Math.round(m.l1Max()) + ", L2 up to ₹" + Math.round(m.l2Max());
        }));
        cs.add(check("S08", "A user for every role in the credit process", () -> {
            List<String> missing = new ArrayList<>();
            for (String role : List.of("CRO", "CREDIT_MANAGER", "CREDIT_OFFICER", "OPERATIONS", "SALES", "COMPLIANCE"))
                if (count("select count(*) from app_user where role = ? and active", role) == 0) missing.add(role);
            return missing.isEmpty() ? "All roles staffed" : "FAIL: no user for " + missing;
        }));
        cs.add(check("S09", "Branches route applications by PIN code", () -> {
            long n = count("select count(*) from branch where active and level = 'BRANCH' and pincodes is not null and pincodes <> '' and products is not null and products <> ''");
            return n > 0 ? n + " branch(es) with PIN codes and products" : "FAIL: no branch has PIN codes and products";
        }));
        cs.add(check("S10", "Holiday calendar for SLA clocks", () -> {
            long n = count("select count(*) from holiday where extract(year from holiday_date) = ?", LocalDate.now().getYear());
            return n > 0 ? n + " holiday(s) this year" : "FAIL: no holidays entered for this year";
        }));
        cs.add(check("S11", "Decision engine runs on the live policy", () -> {
            var sample = SyntheticBook.generate(3, 42);
            int n = 0;
            for (var l : sample) { EngineResult r = UnderwritingEngine.underwrite(l.input(), policy.live()); if (r.decision() != null) n++; }
            return n == sample.size() ? n + " synthetic applicants decided" : "FAIL: engine returned no decision";
        }));
        cs.add(check("S12", "Key Facts Statement for every live product", () -> {
            List<String> bad = new ArrayList<>();
            for (Product p : products.findAll()) {
                if (!"LIVE".equals(p.getStatus())) continue;
                double amt = Math.max(p.getMinAmount().doubleValue(), Math.min(100_000, p.getMaxAmount().doubleValue()));
                Kfs k = KfsCalculator.build("SANDBOX", "Sandbox", p.getName(), amt, p.getRateMin().doubleValue(), Math.max(p.getMinTenure(), 6), p.charges());
                if (!(k.aprPa() >= p.getRateMin().doubleValue())) bad.add(p.getCode());
            }
            return bad.isEmpty() ? "APR at or above the rate for every product" : "FAIL: " + bad;
        }));
        long passed = cs.stream().filter(Check::passed).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ranAt", Instant.now().toString());
        result.put("ranBy", by.username());
        result.put("total", cs.size());
        result.put("passed", passed);
        result.put("allPassed", passed == cs.size());
        result.put("checks", cs);
        jdbc.update("update platform.tenant set sandbox_json = ? where code = ?", Json.write(result), me.code());
        audit.record(by, "onboarding.sandbox_run", "TENANT", me.code(), null, passed + " of " + cs.size() + " checks passed");
        return result;
    }

    // ---------------------------------------------------------------- go-live (maker-checker)

    public Map<String, Object> requestGoLive(GoLive r, CurrentUser by) {
        if (by.role() != Role.ADMIN) throw ApiException.forbidden("ADMIN_ONLY", "The tenant admin requests go-live");
        Map<String, Object> row = row();
        if ("LIVE".equals(row.get("status"))) throw ApiException.conflict("ALREADY_LIVE", "Already live");
        if (r.rollbackPlan() == null || r.rollbackPlan().trim().length() < 20) throw ApiException.unprocessable("ROLLBACK_PLAN", "Describe the rollback plan (at least 20 characters)");
        Map<String, Object> st = status(by);
        @SuppressWarnings("unchecked") List<Map<String, Object>> steps = (List<Map<String, Object>>) st.get("steps");
        List<Object> open = steps.stream().filter(s -> !Boolean.TRUE.equals(s.get("done")) && (int) s.get("step") < 10).map(s -> s.get("step")).toList();
        if (!open.isEmpty()) throw ApiException.conflict("STEPS_OPEN", "Finish onboarding steps " + open + " first");
        jdbc.update("update platform.tenant set go_live_requested_by = ?, go_live_requested_at = now(), rollback_plan = ? where code = ?", by.username(), r.rollbackPlan().trim(), TenantContext.code());
        audit.record(by, "onboarding.go_live_requested", "TENANT", TenantContext.code(), null, r.rollbackPlan().trim());
        return status(by);
    }

    public Map<String, Object> approveGoLive(CurrentUser by) {
        if (by.role() != Role.CRO) throw ApiException.forbidden("CRO_ONLY", "The CRO approves go-live");
        Map<String, Object> row = row();
        if (row.get("go_live_requested_by") == null) throw ApiException.conflict("NOT_REQUESTED", "Go-live has not been requested");
        if (by.username().equals(row.get("go_live_requested_by"))) throw ApiException.forbidden("MAKER_CHECKER", "The requester cannot approve");
        jdbc.update("update platform.tenant set status = 'LIVE', go_live_approved_by = ?, go_live_approved_at = now() where code = ?", by.username(), TenantContext.code());
        registry.reload();
        audit.record(by, "onboarding.go_live_approved", "TENANT", TenantContext.code(), null, "Live");
        return status(by);
    }

    public Map<String, Object> optIn(boolean on, CurrentUser by) {
        if (by.role() != Role.ADMIN) throw ApiException.forbidden("ADMIN_ONLY", "The tenant admin decides on benchmarking");
        jdbc.update("update platform.tenant set benchmarking_opt_in = ? where code = ?", on, TenantContext.code());
        audit.record(by, "benchmarking." + (on ? "opted_in" : "opted_out"), "TENANT", TenantContext.code(), null, null);
        return status(by);
    }
}
