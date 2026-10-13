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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Corrections from the review against the prototype and BRD/FSD: masking, branch scope, INCOMPLETE decisions. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-corrections",
        "rhythm.workflow.sla-initial-delay-ms=86400000"
})
class CorrectionsIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    record Res(int status, JsonNode body) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
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

    String staff(String user, String password) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", user, "password", password)).body().get("token").asText();
    }

    String staff(String user) throws Exception { return staff(user, "Rhythm@123"); }

    Map<String, Object> application(String name, String pan, String mobile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", "1990-04-15"); m.put("city", "Pune"); m.put("pincode", "411038"); m.put("businessName", "Coastal Systems");
        m.put("businessVintageYears", 5); m.put("declaredMonthlyIncome", 80000); m.put("essentialExpenses", 25000);
        m.put("loanAmount", 200000); m.put("tenureMonths", 24); m.put("bankAccountNo", "501001234567"); m.put("bankIfsc", "HDFC0001234");
        m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        return m;
    }

    void upload(long id, String token, String... types) throws Exception {
        for (String t : types) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
    }

    @Test
    void panAndAccountAreMaskedExceptInTheDraftForm() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1");
        long id = ok(post("/api/applications"), sales, application("Mask Person", "MASPM1234M", "9811192001")).body().get("id").asLong();
        JsonNode v = ok(get("/api/applications/" + id), ops, null).body().get("application");
        assertThat(v.get("pan").asText()).isEqualTo("MAxxxxxx4M");
        assertThat(v.get("bankAccountNo").asText()).isEqualTo("xxxxxxxx4567");
        assertThat(ok(get("/api/applications"), ops, null).body().toString()).doesNotContain("MASPM1234M");
        JsonNode draft = ok(get("/api/applications/" + id + "/draft"), sales, null).body();
        assertThat(draft.get("pan").asText()).isEqualTo("MASPM1234M");
        assertThat(draft.get("bankAccountNo").asText()).isEqualTo("501001234567");
        ok(post("/api/applications/" + id + "/submit"), sales, null);
        assertThat(call(get("/api/applications/" + id + "/draft"), sales, null).status()).isEqualTo(409);
        // duplicate check message does not reveal the PAN either
        assertThat(call(post("/api/applications"), sales, application("Mask Person", "MASPM1234M", "9811192001")).body().toString())
                .contains("DUPLICATE_PAN").doesNotContain("MASPM1234M");
    }

    @Test
    void staffSeeOnlyTheirBranchAndTheUnitsBelowIt() throws Exception {
        String admin = staff("admin"), sales = staff("sales1");
        long pune = 0;
        for (JsonNode b : ok(get("/api/branches"), admin, null).body()) if ("BPUN".equals(b.get("code").asText())) pune = b.get("id").asLong();
        ok(post("/api/admin/users"), admin, Map.of("username", "ops_pune", "fullName", "Pune Ops", "role", "OPERATIONS",
                "branchId", pune, "password", "Pune@12345"));
        String punOps = staff("ops_pune", "Pune@12345");
        long id = ok(post("/api/applications"), sales, application("Scope Person", "SCOPS1234S", "9811192002")).body().get("id").asLong();
        // created by sales1 at Nashik: the Pune branch cannot see it, the region (cm1) and head office (cro1) can
        assertThat(call(get("/api/applications/" + id), punOps, null).status()).isEqualTo(404);
        assertThat(call(get("/api/applications/" + id + "/documents"), punOps, null).status()).isEqualTo(404);
        assertThat(ok(get("/api/applications"), punOps, null).body().toString()).doesNotContain("Scope Person");
        assertThat(ok(get("/api/applications/" + id), staff("cm1"), null).body().get("application").get("applicantName").asText()).isEqualTo("Scope Person");
        assertThat(ok(get("/api/applications"), staff("cro1"), null).body().toString()).contains("Scope Person");
        assertThat(ok(get("/api/applications"), staff("ops1"), null).body().toString()).contains("Scope Person");
    }

    @Test
    void tooLittleBankHistoryGivesAnIncompleteDecisionNotAReferral() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1");
        long id = ok(post("/api/applications"), sales, application("Short History", "SHOPH9601S", "9811192003")).body().get("id").asLong();
        upload(id, sales, "PAN", "AADHAAR", "SALARY_SLIP");
        String steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps").toString();
        assertThat(steps).contains("Incomplete, missing").contains("6 months");
        JsonNode app = ok(get("/api/applications/" + id), ops, null).body();
        assertThat(app.get("states").get("DECISION").asText()).isEqualTo("INCOMPLETE");
        assertThat(app.get("states").get("SANCTION").asText()).isEqualTo("NOT_STARTED");
        JsonNode d = ok(get("/api/applications/" + id + "/decision"), ops, null).body();
        assertThat(d.get("decision").asText()).isEqualTo("INCOMPLETE");
        assertThat(d.get("missingData").asText()).contains("6 months");
        assertThat(d.get("reasonCodes").asText()).contains("RC-013");
        assertThat(d.has("delegationLevel")).isFalse();
        // running again with nothing new does not add another decision record
        long first = d.get("id").asLong();
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(ok(get("/api/applications/" + id + "/decision"), ops, null).body().get("id").asLong()).isEqualTo(first);
    }
}
