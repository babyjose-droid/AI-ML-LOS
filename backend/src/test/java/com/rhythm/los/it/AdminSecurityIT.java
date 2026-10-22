package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.security.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 1 admin and security: lockout, MFA, branch PIN codes and geography, masters, sanction limits, integration hub, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-admin",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class AdminSecurityIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    record Res(int status, JsonNode body, String raw) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        MvcResult r = mvc.perform(b).andReturn();
        String s = r.getResponse().getContentAsString();
        JsonNode n = null;
        try { n = s.isEmpty() ? null : Json.MAPPER.readTree(s); } catch (Exception ignored) { }
        return new Res(r.getResponse().getStatus(), n, s);
    }

    Res ok(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        Res r = call(b, token, body);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r;
    }

    String login(String user, String password) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", user, "password", password)).body().get("token").asText();
    }

    String login(String user) throws Exception { return login(user, "Rhythm@123"); }

    long userId(String admin, String username) throws Exception {
        for (JsonNode u : ok(get("/api/admin/users"), admin, null).body()) if (username.equals(u.get("username").asText())) return u.get("id").asLong();
        throw new AssertionError(username);
    }

    long branchId(String token, String code) throws Exception {
        for (JsonNode b : ok(get("/api/branches"), token, null).body()) if (code.equals(b.get("code").asText())) return b.get("id").asLong();
        throw new AssertionError(code);
    }

    Map<String, Object> app(String name, String pan, String mobile, String pin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(34).minusMonths(1).toString()); m.put("city", "Mumbai"); m.put("address", "1 Test Road");
        m.put("pincode", pin); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000); m.put("essentialExpenses", 30000);
        m.put("loanAmount", 300000); m.put("tenureMonths", 24); m.put("purpose", "Test"); m.put("bankAccountNo", "5010012345678");
        m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        return m;
    }

    long create(String token, Map<String, Object> body, String... docs) throws Exception {
        long id = ok(post("/api/applications"), token, body).body().get("id").asLong();
        for (String t : docs) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
        return id;
    }

    @Test
    void fiveWrongPasswordsLockTheAccountUntilTheAdminUnlocksIt() throws Exception {
        String admin = login("admin");
        ok(post("/api/admin/users"), admin, Map.of("username", "lock.me", "fullName", "Lock Me", "role", "SALES", "password", "Lock@12345"));
        for (int i = 0; i < 5; i++) assertThat(call(post("/api/auth/login"), null, Map.of("username", "lock.me", "password", "wrong-" + i)).status()).isEqualTo(401);
        Res locked = call(post("/api/auth/login"), null, Map.of("username", "lock.me", "password", "Lock@12345"));
        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.body().get("code").asText()).isEqualTo("ACCOUNT_LOCKED");
        long id = userId(admin, "lock.me");
        JsonNode users = ok(get("/api/admin/users"), admin, null).body();
        assertThat(users.toString()).contains("\"locked\":true");
        ok(post("/api/admin/users/" + id + "/unlock"), admin, null);
        login("lock.me", "Lock@12345");
        assertThat(ok(get("/api/audit"), admin, null).body().toString()).contains("auth.locked").contains("user.unlock");
    }

    @Test
    void mfaWithAnAuthenticatorApp() throws Exception {
        String admin = login("admin");
        ok(post("/api/admin/users"), admin, Map.of("username", "mfa.user", "fullName", "Mfa User", "role", "OPERATIONS", "password", "Mfa@123456"));
        String t = login("mfa.user", "Mfa@123456");
        JsonNode setup = ok(post("/api/auth/mfa/setup"), t, null).body();
        String secret = setup.get("secret").asText();
        assertThat(setup.get("otpauthUri").asText()).startsWith("otpauth://totp/").contains("secret=" + secret);
        assertThat(call(post("/api/auth/mfa/enable"), t, Map.of("code", "000000")).status()).isEqualTo(422);
        String code = Totp.code(secret, Instant.now().getEpochSecond() / 30);
        assertThat(ok(post("/api/auth/mfa/enable"), t, Map.of("code", code)).body().get("mfaEnabled").asBoolean()).isTrue();
        assertThat(ok(get("/api/admin/users"), admin, null).raw()).doesNotContain(secret);

        Res need = call(post("/api/auth/login"), null, Map.of("username", "mfa.user", "password", "Mfa@123456"));
        assertThat(need.status()).isEqualTo(401);
        assertThat(need.body().get("code").asText()).isEqualTo("MFA_REQUIRED");
        Map<String, Object> wrong = new HashMap<>(Map.of("username", "mfa.user", "password", "Mfa@123456", "otp", "123123"));
        if (Totp.verify(secret, "123123", Instant.now().getEpochSecond())) wrong.put("otp", "321321");
        assertThat(call(post("/api/auth/login"), null, wrong).body().get("code").asText()).isEqualTo("BAD_OTP");
        Res in = ok(post("/api/auth/login"), null, Map.of("username", "mfa.user", "password", "Mfa@123456",
                "otp", Totp.code(secret, Instant.now().getEpochSecond() / 30)));
        assertThat(in.body().get("idleTimeoutMinutes").asInt()).isPositive();

        ok(post("/api/admin/users/" + userId(admin, "mfa.user") + "/mfa-reset"), admin, null);
        login("mfa.user", "Mfa@123456");
    }

    @Test
    void branchPinCodesDriveTheGeographyRuleAndBranchesCanBeEditedAndDeactivated() throws Exception {
        String admin = login("admin"), sales = login("sales1"), ops = login("ops1");
        long bnsk = branchId(admin, "BNSK"), bpun = branchId(admin, "BPUN"), rpn = branchId(admin, "RPN");
        Map<String, Object> nashik = new LinkedHashMap<>(Map.of("code", "BNSK", "name", "Nashik Branch", "level", "BRANCH", "parentId", rpn, "city", "Nashik"));
        try {
            nashik.put("pincodes", "422001 12345");
            assertThat(call(put("/api/admin/branches/" + bnsk), admin, nashik).body().get("code").asText()).isEqualTo("BAD_PINCODE");
            nashik.put("pincodes", "422001, 422002 422002");
            JsonNode b = ok(put("/api/admin/branches/" + bnsk), admin, nashik).body();
            assertThat(b.get("pincodes").asText()).isEqualTo("422001,422002");
            Map<String, Object> pune = new LinkedHashMap<>(Map.of("code", "BPUN", "name", "Pune City Branch", "level", "BRANCH", "parentId", rpn, "city", "Pune", "pincodes", "411038,422001"));
            assertThat(call(put("/api/admin/branches/" + bpun), admin, pune).body().get("code").asText()).isEqualTo("PIN_TAKEN");
            assertThat(call(put("/api/admin/branches/" + bnsk), sales, nashik).status()).isEqualTo(403);
            nashik.put("code", "BNSX");
            assertThat(call(put("/api/admin/branches/" + bnsk), admin, nashik).body().get("code").asText()).isEqualTo("CODE_FIXED");
            nashik.put("code", "BNSK");

            // an applicant whose PIN code no branch serves is declined by R18
            long id = create(sales, app("Far Away", "FARPA1002F", "9833000001", "400001"), "PAN", "AADHAAR", "SALARY_SLIP");
            ok(post("/api/applications/" + id + "/process"), ops, null);
            JsonNode d = ok(get("/api/applications/" + id + "/decision"), ops, null).body();
            assertThat(d.get("decision").asText()).isEqualTo("REJECT");
            assertThat(d.get("rulesJson").asText()).contains("R18");
            assertThat(d.get("reasonCodes").asText()).contains("RC-022");
            // one from a served PIN code passes the rule
            long ok = create(sales, app("Near By", "NEAPB1002N", "9833000002", "422002"), "PAN", "AADHAAR", "SALARY_SLIP");
            ok(post("/api/applications/" + ok + "/process"), ops, null);
            assertThat(ok(get("/api/applications/" + ok + "/decision"), ops, null).body().get("decision").asText()).isNotEqualTo("REJECT");

            // customer 360 and the stage board see it
            assertThat(ok(get("/api/applications/" + ok + "/customer"), ops, null).body().get("applications").size()).isEqualTo(1);
            assertThat(ok(get("/api/pipeline/board"), ops, null).body().toString()).contains("Near By");
            assertThat(ok(get("/api/overview"), ops, null).body().get("byState").has("UNDERWRITING")).isTrue();
        } finally {
            nashik.put("pincodes", "");
            ok(put("/api/admin/branches/" + bnsk), admin, nashik);
        }
        assertThat(call(patch("/api/admin/branches/" + rpn + "/active?value=false"), admin, Map.of()).body().get("code").asText()).isEqualTo("HAS_ACTIVE_UNITS");
        assertThat(call(patch("/api/admin/branches/" + bnsk + "/active?value=false"), admin, Map.of()).body().get("code").asText()).isEqualTo("HAS_ACTIVE_USERS");
        assertThat(ok(patch("/api/admin/branches/" + bpun + "/active?value=false"), admin, Map.of()).body().get("active").asBoolean()).isFalse();
        ok(patch("/api/admin/branches/" + bpun + "/active?value=true"), admin, Map.of());
    }

    @Test
    void holidaysAndSanctionLimitsAreMasters() throws Exception {
        String admin = login("admin"), co = login("co1");
        assertThat(ok(get("/api/masters/holidays"), co, null).body().toString()).contains("Diwali");
        assertThat(call(post("/api/admin/holidays"), co, Map.of("date", "2027-03-14", "name", "Holi")).status()).isEqualTo(403);
        long h = ok(post("/api/admin/holidays"), admin, Map.of("date", "2027-03-14", "name", "Holi")).body().get("id").asLong();
        assertThat(call(post("/api/admin/holidays"), admin, Map.of("date", "2027-03-14", "name", "Holi again")).body().get("code").asText()).isEqualTo("DUPLICATE_HOLIDAY");
        assertThat(call(delete("/api/admin/holidays/" + h), admin, null).status()).isEqualTo(204);

        JsonNode roles = ok(get("/api/masters/roles"), co, null).body();
        assertThat(roles.get("limits").size()).isEqualTo(3);
        assertThat(roles.get("roles").toString()).contains("CREDIT_OFFICER").contains("Sanction within L1 limits");
        assertThat(call(put("/api/admin/sanction-limits/L1"), admin, Map.of("maxAmount", 300000)).body().get("code").asText()).isEqualTo("REASON_REQUIRED");
        assertThat(call(put("/api/admin/sanction-limits/L1"), admin, Map.of("maxAmount", 2000000, "note", "Board meeting 12 Oct 2026")).body().get("code").asText()).isEqualTo("INCONSISTENT");
        try {
            JsonNode l1 = ok(put("/api/admin/sanction-limits/L1"), admin, Map.of("maxAmount", 300000, "note", "Board meeting 12 Oct 2026")).body();
            assertThat(l1.get("maxAmount").asDouble()).isEqualTo(300000);
            assertThat(com.rhythm.los.decision.Delegation.levelForAmount(250_000)).isEqualTo("L1");
        } finally {
            ok(put("/api/admin/sanction-limits/L1"), admin, Map.of("maxAmount", 200000, "note", "Back to the board-approved value"));
        }
        assertThat(com.rhythm.los.decision.Delegation.levelForAmount(250_000)).isEqualTo("L2");
    }

    @Test
    void integrationHubConnectorsBreakerBulkRetryAndMasking() throws Exception {
        String admin = login("admin"), ops = login("ops1"), sales = login("sales1");
        JsonNode cs = ok(get("/api/integrations/connectors"), ops, null).body();
        assertThat(cs.size()).isEqualTo(10);
        assertThat(cs.toString()).contains("\"connector\":\"bureau\"").contains("CLOSED");
        assertThat(call(put("/api/admin/connectors/bureau"), ops, Map.of("enabled", false)).status()).isEqualTo(403);
        assertThat(call(put("/api/admin/connectors/bureau"), admin, Map.of("timeoutMs", 10)).body().get("code").asText()).isEqualTo("OUT_OF_RANGE");
        assertThat(call(put("/api/admin/connectors/bureau"), admin, Map.of("baseUrl", "http://insecure.example")).body().get("code").asText()).isEqualTo("BAD_URL");

        ok(put("/api/admin/connectors/bureau"), admin, Map.of("enabled", false));
        long id;
        try {
            id = create(sales, app("Hub Person", "HUBPP1002H", "9833000003", "422001"), "PAN", "AADHAAR", "SALARY_SLIP");
            ok(post("/api/applications/" + id + "/process"), ops, null);
        } finally {
            ok(put("/api/admin/connectors/bureau"), admin, Map.of("enabled", true));
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode l : ok(get("/api/integrations/logs?status=DISABLED"), ops, null).body())
            if (l.get("applicationId").asLong() == id) ids.add(l.get("id").asLong());
        assertThat(ids).isNotEmpty();
        assertThat(call(post("/api/integrations/logs/retry-bulk"), ops, Map.of("ids", List.of())).status()).isEqualTo(422);
        JsonNode res = ok(post("/api/integrations/logs/retry-bulk"), ops, Map.of("ids", ids)).body();
        assertThat(res.get(0).get("ok").asBoolean()).as(res.toString()).isTrue();
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("DATA").asText()).isEqualTo("FETCHED");

        // vendor log bodies never carry a full PAN
        assertThat(ok(get("/api/applications/" + id + "/integrations"), ops, null).raw()).doesNotContain("HUBPP1002H");
        assertThat(com.rhythm.los.common.Mask.text("PAN ABCPK1234L acct 501001234567 aadhaar 2345 6789 0123"))
                .doesNotContain("ABCPK1234L").doesNotContain("501001234567").contains("0123");

        assertThat(ok(post("/api/admin/connectors/bureau/reset-breaker"), admin, null).body().get("state").asText()).isEqualTo("CLOSED");
    }

    @Test
    void auditTrailIsAppendOnlyAndExportable() throws Exception {
        String admin = login("admin"), comp = login("comp1"), sales = login("sales1");
        java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
        String today = LocalDate.now(ist).toString(), yesterday = LocalDate.now(ist).minusDays(1).toString();
        Res csv = call(get("/api/audit/export?from=" + yesterday + "&to=" + today + "&action=auth."), comp, null);
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.raw()).startsWith("id,time,actor,role,action").contains("auth.login").doesNotContain("user.create");
        assertThat(call(get("/api/audit/export?from=" + today + "&to=" + yesterday), comp, null).status()).isEqualTo(422);
        assertThat(call(get("/api/audit/export?from=" + yesterday + "&to=" + today), sales, null).status()).isEqualTo(403);
        assertThat(ok(get("/api/audit"), admin, null).body().toString()).contains("audit.exported");
        assertThatThrownBy(() -> jdbc.update("update audit_event set details = 'changed'")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_event")).hasMessageContaining("append-only");
    }
}
