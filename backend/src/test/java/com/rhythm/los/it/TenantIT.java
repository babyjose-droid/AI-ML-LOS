package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 4C: multi-tenancy (schema per tenant), tenant onboarding with the sandbox pack and go-live maker-checker, suspension, benchmarking. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-tenant",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class TenantIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    record Res(int status, JsonNode body) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception { return call(b, token, null, body); }

    Res call(MockHttpServletRequestBuilder b, String token, String tenant, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (tenant != null) b.header("X-Tenant", tenant);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        MvcResult r = mvc.perform(b).andReturn();
        String s = r.getResponse().getContentAsString();
        JsonNode n = null;
        try { n = s.isEmpty() ? null : Json.MAPPER.readTree(s); } catch (Exception ignored) { }
        return new Res(r.getResponse().getStatus(), n);
    }

    Res ok(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        Res r = call(b, token, body);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return r;
    }

    String login(String tenant, String user, String password) throws Exception {
        Res r = call(post("/api/auth/login"), null, tenant, Map.of("username", user, "password", password));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return r.body().get("token").asText();
    }

    String staff(String u) throws Exception { return login(null, u, "Rhythm@123"); }

    @Test
    void tenantsAreIsolatedOnboardedAndGoLiveWithMakerChecker() throws Exception {
        String admin = staff("admin"), sales = staff("sales1");
        // a default-tenant application, to prove the new tenant cannot see it
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", "Default Tenant Person"); m.put("pan", "DEFPT1002T"); m.put("mobile", "9879000001");
        m.put("dob", LocalDate.now().minusYears(34).toString()); m.put("city", "Nashik"); m.put("address", "1 Road"); m.put("pincode", "422001");
        m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000); m.put("essentialExpenses", 30000); m.put("loanAmount", 300000);
        m.put("tenureMonths", 24); m.put("purpose", "Test"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        ok(post("/api/applications"), sales, m);

        // platform: create a tenant
        assertThat(call(post("/api/platform/tenants"), sales, Map.of("code", "acme", "name", "Acme Finance")).status()).isEqualTo(403);
        assertThat(call(post("/api/platform/tenants"), admin, Map.of("code", "1x", "name", "Bad")).status()).isEqualTo(422);
        JsonNode t = ok(post("/api/platform/tenants"), admin, Map.of("code", "acme", "name", "Acme Finance")).body();
        assertThat(t.get("schema").asText()).isEqualTo("t_acme");
        String pw = t.get("adminPassword").asText();
        assertThat(call(post("/api/platform/tenants"), admin, Map.of("code", "acme", "name", "Again")).status()).isEqualTo(409);

        // sign-in is per tenant
        assertThat(call(post("/api/auth/login"), null, "nosuch", Map.of("username", "admin", "password", pw)).status()).isEqualTo(404);
        assertThat(call(post("/api/auth/login"), null, "acme", Map.of("username", "sales1", "password", "Rhythm@123")).status()).isEqualTo(401);
        String acme = login("acme", "admin", pw);
        JsonNode me = ok(get("/api/tenant"), acme, null).body();
        assertThat(me.get("code").asText()).isEqualTo("acme");
        assertThat(me.get("status").asText()).isEqualTo("ONBOARDING");
        // isolation: the default tenant's application is not visible; a token cannot be used for another tenant
        assertThat(ok(get("/api/applications"), acme, null).body().toString()).doesNotContain("Default Tenant Person");
        assertThat(ok(get("/api/applications"), admin, null).body().toString()).contains("Default Tenant Person");
        assertThat(call(get("/api/applications"), acme, "default", null).status()).isEqualTo(403);
        assertThat(call(get("/api/platform/tenants"), acme, null).status()).isEqualTo(403);
        assertThat(ok(get("/api/admin/users"), acme, null).body().size()).isEqualTo(1);

        // onboarding
        JsonNode ob = ok(get("/api/onboarding"), acme, null).body();
        assertThat(ob.get("steps").size()).isEqualTo(10);
        ok(post("/api/onboarding/steps/2/confirm"), acme, null);
        assertThat(call(post("/api/onboarding/steps/3/confirm"), acme, null).status()).isEqualTo(409);
        assertThat(call(post("/api/onboarding/steps/1/confirm"), acme, null).status()).isEqualTo(422);
        long ho = 0;
        for (JsonNode b : ok(get("/api/branches"), acme, null).body()) if ("HO".equals(b.get("code").asText())) ho = b.get("id").asLong();
        long br = ok(post("/api/admin/branches"), acme, Map.of("code", "ACM1", "name", "Acme Indore", "level", "BRANCH", "parentId", ho, "city", "Indore",
                "pincodes", "452001", "products", "PL,BL-UNS")).body().get("id").asLong();
        for (String[] u : new String[][]{{"acmecro", "CRO"}, {"acmecm", "CREDIT_MANAGER"}, {"acmeco", "CREDIT_OFFICER"}, {"acmeops", "OPERATIONS"}, {"acmesales", "SALES"}, {"acmecomp", "COMPLIANCE"}})
            ok(post("/api/admin/users"), acme, Map.of("username", u[0], "fullName", u[0] + " user", "role", u[1], "branchId", br, "password", "Acme@12345"));
        // national holidays come with the template; a lender adds its own
        assertThat(call(post("/api/admin/holidays"), acme, Map.of("date", LocalDate.now().getYear() + "-07-07", "name", "Acme foundation day")).status()).isIn(200, 409);
        assertThat(call(post("/api/onboarding/go-live/request"), acme, Map.of("rollbackPlan", "Switch back to the old LOS; data export ready")).status()).isEqualTo(409);

        JsonNode sb = ok(post("/api/onboarding/sandbox"), acme, null).body();
        assertThat(sb.get("total").asInt()).isEqualTo(12);
        assertThat(sb.get("allPassed").asBoolean()).as(sb.get("checks").toString()).isTrue();
        assertThat(sb.get("checks").toString()).contains("not visible from tenant default");
        for (int s : List.of(3, 4, 5, 6, 8)) ok(post("/api/onboarding/steps/" + s + "/confirm"), acme, null);
        ob = ok(get("/api/onboarding"), acme, null).body();
        for (JsonNode s : ob.get("steps")) if (s.get("step").asInt() < 10) assertThat(s.get("done").asBoolean()).as(s.toString()).isTrue();
        assertThat(call(post("/api/onboarding/go-live/request"), acme, Map.of("rollbackPlan", "short")).status()).isEqualTo(422);
        ok(post("/api/onboarding/go-live/request"), acme, Map.of("rollbackPlan", "Switch back to the old LOS; data export ready; vendors notified"));
        assertThat(call(post("/api/onboarding/go-live/approve"), acme, null).status()).isEqualTo(403);
        String acmeCro = login("acme", "acmecro", "Acme@12345");
        JsonNode live = ok(post("/api/onboarding/go-live/approve"), acmeCro, null).body();
        assertThat(live.get("status").asText()).isEqualTo("LIVE");

        // the tenant works: its own application, numbered in its own sequence, invisible to the default tenant
        String acmeSales = login("acme", "acmesales", "Acme@12345");
        Map<String, Object> m2 = new LinkedHashMap<>(m);
        m2.put("applicantName", "Acme Customer"); m2.put("pan", "ACMPC1002C"); m2.put("mobile", "9879000002"); m2.put("pincode", "452001");
        JsonNode acmeApp = ok(post("/api/applications"), acmeSales, m2).body();
        assertThat(acmeApp.get("appNo").asText()).isEqualTo("APP-300001");
        assertThat(ok(get("/api/applications"), admin, null).body().toString()).doesNotContain("Acme Customer");

        // suspension
        ok(post("/api/platform/tenants/acme/suspend"), admin, null);
        assertThat(call(get("/api/applications"), acmeSales, null).status()).isEqualTo(403);
        ok(post("/api/platform/tenants/acme/reactivate"), admin, null);
        assertThat(ok(get("/api/tenant"), acmeSales, null).body().get("status").asText()).isEqualTo("LIVE");
        JsonNode list = ok(get("/api/platform/tenants"), admin, null).body();
        assertThat(list.toString()).contains("acme").contains("Acme Finance");

        // benchmarking: opt in; fewer than five lenders, so no peer figures
        ok(post("/api/onboarding/benchmarking"), acme, Map.of("optIn", true));
        assertThat(ok(post("/api/benchmarking/refresh"), admin, null).body().get("tenants").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode bm = ok(get("/api/benchmarking"), acme, null).body();
        assertThat(bm.get("optedIn").asBoolean()).isTrue();
        assertThat(bm.get("metrics").size()).isEqualTo(7);
        assertThat(bm.get("metrics").get(0).has("median")).isFalse();
    }
}
