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

/** Phase 2 analytics: network risk, household cash flow, industry scorecards, device and industry capture. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-phase2",
        "rhythm.network.enabled=true",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class PhaseTwoAnalyticsIT {

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
    void sharedBankAccountAndDeviceBuildTheNetworkAndRaiseTheScore() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        Map<String, Object> a = app("Net One", "NETPO1002A", "9844000001", "422001");
        a.put("bankAccountNo", "777000111222");
        a.put("address", "5 Net Lane");
        Map<String, Object> b = app("Net Two", "NETPT1002B", "9844000002", "422001");
        b.put("bankAccountNo", "777000111222");
        b.put("address", "5 Net Lane");
        long first = ok(post("/api/applications").header("X-Device-Id", "device-abc-123"), sales, a).body().get("id").asLong();
        long second = ok(post("/api/applications").header("X-Device-Id", "device-abc-123"), sales, b).body().get("id").asLong();
        JsonNode net = ok(get("/api/applications/" + second + "/network"), ops, null).body();
        assertThat(net.get("score").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(net.get("factors").toString()).contains("Bank account shared").contains("Device shared");
        assertThat(net.get("nodes").size()).isEqualTo(2);
        assertThat(net.get("edges").get(0).get("via").asText()).contains("bank account").contains("device");
        // the device id is never returned
        assertThat(ok(get("/api/applications/" + first), ops, null).raw()).doesNotContain("device-abc-123");

        // the decision carries the network score
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) upload(second, sales, t);
        ok(post("/api/applications/" + second + "/process"), ops, null);
        JsonNode d = ok(get("/api/applications/" + second + "/decision"), ops, null).body();
        assertThat(d.get("networkScore").asInt()).isGreaterThanOrEqualTo(35);
        assertThat(d.get("rulesJson").asText()).contains("R22");
        // a salaried applicant is scored on the salaried scorecard as a challenger
        assertThat(d.get("scorecardJson").asText()).contains("SC-SALARIED");
    }

    @Test
    void householdIsCapturedValidatedAndShown() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1");
        Map<String, Object> body = app("House Hold", "HOUPH1002H", "9844000003", "422001");
        body.put("industry", "KIRANA");
        body.put("address", "9 Hold Street");
        body.put("addressLat", 19.9975);
        body.put("addressLng", 73.7898);
        JsonNode created = ok(post("/api/applications"), sales, body).body();
        long id = created.get("id").asLong();
        assertThat(created.get("industry").asText()).isEqualTo("KIRANA");
        assertThat(created.get("addressLat").asDouble()).isEqualTo(19.9975);
        body.put("industry", "SPACESHIPS");
        body.put("pan", "HOUPX1002H");
        body.put("mobile", "9844000013");
        assertThat(call(post("/api/applications"), sales, body).status()).isEqualTo(400);

        assertThat(ok(get("/api/applications/" + id + "/household"), ops, null).body().has("household")).isFalse();
        Map<String, Object> hh = new LinkedHashMap<>();
        hh.put("members", 4); hh.put("monthlyExpenses", 9000); hh.put("existingEmi", 2000);
        hh.put("earners", List.of());
        assertThat(call(put("/api/applications/" + id + "/household"), sales, hh).body().get("code").asText()).isEqualTo("NO_EARNERS");
        hh.put("earners", List.of(Map.of("name", "House Hold", "relation", "BOSS", "monthlyIncome", 1000)));
        assertThat(call(put("/api/applications/" + id + "/household"), sales, hh).body().get("code").asText()).isEqualTo("BAD_RELATION");
        hh.put("earners", List.of(Map.of("name", "House Hold", "relation", "SELF", "occupation", "Shop", "monthlyIncome", 15000),
                Map.of("name", "Spouse", "relation", "SPOUSE", "occupation", "Tailoring", "monthlyIncome", 6000),
                Map.of("name", "Son", "relation", "SON", "occupation", "Student", "monthlyIncome", 0, "included", false)));
        assertThat(call(put("/api/applications/" + id + "/household"), login("fraud1"), hh).status()).isEqualTo(403);
        JsonNode v = ok(put("/api/applications/" + id + "/household"), sales, hh).body();
        assertThat(v.get("monthlyIncome").asDouble()).isEqualTo(21000);
        assertThat(v.get("surplus").asDouble()).isEqualTo(10000);
        assertThat(v.get("mfiQualifying").asBoolean()).isTrue();
        assertThat(v.get("mfiRepaymentRoom").asDouble()).isEqualTo(8500);
        assertThat(v.get("earners").size()).isEqualTo(3);
        // saving again replaces the members
        hh.put("earners", List.of(Map.of("name", "House Hold", "relation", "SELF", "monthlyIncome", 15000)));
        assertThat(ok(put("/api/applications/" + id + "/household"), co, hh).body().get("earners").size()).isEqualTo(1);
    }

    @Test
    void scorecardsAreListedAndOnlyValidEditsAreSaved() throws Exception {
        String admin = login("admin"), co = login("co1");
        JsonNode list = ok(get("/api/scorecards"), co, null).body();
        assertThat(list.size()).isEqualTo(5);
        JsonNode kirana = null;
        for (JsonNode d : list) if ("SC-KIRANA".equals(d.get("code").asText())) kirana = d;
        assertThat(kirana).isNotNull();
        Map<String, Object> def = Json.MAPPER.convertValue(kirana, Map.class);
        assertThat(call(put("/api/admin/scorecards/SC-KIRANA"), co, def).status()).isEqualTo(403);
        ok(put("/api/admin/scorecards/SC-KIRANA"), admin, def);
        @SuppressWarnings("unchecked") List<Map<String, Object>> chars = (List<Map<String, Object>>) def.get("characteristics");
        chars.get(0).put("bins", List.of(Map.of("max", 3, "points", 10), Map.of("max", 1, "points", 5), Map.of("points", 0)));
        assertThat(call(put("/api/admin/scorecards/SC-KIRANA"), admin, def).body().get("code").asText()).isEqualTo("BAD_BINS");
    }

    void upload(long id, String token, String type) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                .file(new MockMultipartFile("file", type.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                .param("type", type).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }
}
