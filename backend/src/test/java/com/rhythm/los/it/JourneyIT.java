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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * End-to-end journeys through the real API, database and state machine (vendors are the mocks).
 * Each test uses its own PAN so tests are independent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents"
})
class JourneyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    // ---------- helpers ----------

    String login(String user) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"Rhythm@123\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).as("login " + user).isEqualTo(200);
        return Json.MAPPER.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    record Res(int status, JsonNode body) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        MvcResult r = mvc.perform(b).andReturn();
        String s = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), s.isEmpty() ? null : Json.MAPPER.readTree(s));
    }

    Res ok(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        Res r = call(b, token, body);
        assertThat(r.status()).as(r.body() == null ? "" : r.body().toString()).isEqualTo(200);
        return r;
    }

    Map<String, Object> app(String product, String name, String pan, String mobile, int age, double income, double amount, int tenure) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", product);
        m.put("applicantName", name);
        m.put("pan", pan);
        m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(age).minusMonths(1).toString());
        m.put("city", "Nashik");
        m.put("address", "1 Test Road");
        m.put("pincode", "422001");
        m.put("businessVintageYears", 6);
        m.put("declaredMonthlyIncome", income);
        m.put("essentialExpenses", Math.round(income * 0.35));
        m.put("loanAmount", amount);
        m.put("tenureMonths", tenure);
        m.put("purpose", "Test");
        m.put("bankAccountNo", "5010012345678");
        m.put("bankIfsc", "HDFC0001234");
        m.put("consentBureau", true);
        m.put("consentAa", true);
        m.put("consentKyc", true);
        return m;
    }

    long create(String token, Map<String, Object> body, String... docs) throws Exception {
        long id = ok(post("/api/applications"), token, body).body().get("id").asLong();
        for (String d : docs) {
            String file = d.contains(":") ? d.split(":")[1] : d.toLowerCase() + ".pdf";
            String type = d.split(":")[0];
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", file, "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", type).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
        return id;
    }

    JsonNode states(long id, String token) throws Exception {
        return ok(get("/api/applications/" + id), token, null).body().get("states");
    }

    // ---------- journeys ----------

    @Test
    void j1StraightThroughToDisbursement() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1"), cm = login("cm1");
        Map<String, Object> body = app("PL", "Priya Nair", "PRIPN1002P", "9811000001", 34, 72000, 500000, 24);
        body.put("essentialExpenses", 30000);
        long id = create(sales, body, "PAN", "AADHAAR", "SALARY_SLIP");

        JsonNode steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        assertThat(steps.toString()).contains("APPROVE_WITH_CONDITIONS");
        JsonNode st = states(id, ops);
        assertThat(st.get("APP").asText()).isEqualTo("UNDERWRITING");
        assertThat(st.get("SANCTION").asText()).isEqualTo("PENDING_L2");

        JsonNode d = ok(get("/api/applications/" + id + "/decision"), ops, null).body();
        assertThat(d.get("recommendedAmount").asDouble()).isEqualTo(450000);
        assertThat(d.get("creditMemo").asText()).contains("CREDIT MEMO").contains("Sanction authority");

        // L1 officer cannot sanction an L2 case
        Res denied = call(post("/api/applications/" + id + "/sanction"), co, Map.of());
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.body().get("code").asText()).isEqualTo("ABOVE_AUTHORITY");

        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of());
        assertThat(states(id, cm).get("APP").asText()).isEqualTo("SANCTIONED");

        JsonNode kfs = ok(get("/api/applications/" + id + "/kfs"), sales, null).body();
        assertThat(kfs.get("sanctionedAmount").asDouble()).isEqualTo(450000);
        assertThat(kfs.get("aprPa").asDouble()).isGreaterThan(kfs.get("ratePa").asDouble());

        assertThat(call(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "000000")).status()).isEqualTo(422);
        ok(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "123456"));

        JsonNode disb = ok(post("/api/applications/" + id + "/disburse"), ops, null).body();
        assertThat(disb.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(disb.get("lmsPayload").asText()).contains("externalRef");
        st = states(id, ops);
        assertThat(st.get("APP").asText()).isEqualTo("DISBURSED");
        assertThat(st.get("DISB").asText()).isEqualTo("DISBURSED");

        JsonNode hist = ok(get("/api/applications/" + id + "/history"), ops, null).body();
        assertThat(hist.size()).isGreaterThanOrEqualTo(12);
        JsonNode audit = ok(get("/api/audit?applicationId=" + id), login("comp1"), null).body();
        assertThat(audit.toString()).contains("sanction.approved").contains("kfs.accepted").contains("disbursement.completed");
    }

    @Test
    void invalidTransitionAndDuplicatePanAreRefused() throws Exception {
        String sales = login("sales1");
        long id = create(sales, app("BL-UNS", "Kiran Rao", "KIRPR1111K", "9811000002", 40, 50000, 200000, 24));
        ok(post("/api/applications/" + id + "/submit"), sales, null);
        Res again = call(post("/api/applications/" + id + "/submit"), sales, null);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body().get("code").asText()).isEqualTo("INVALID_TRANSITION");

        Res dup = call(post("/api/applications"), sales, app("BL-UNS", "Kiran Rao", "KIRPR1111K", "9811000002", 40, 50000, 200000, 24));
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.body().get("code").asText()).isEqualTo("DUPLICATE_PAN");

        Res badAmount = call(post("/api/applications"), sales, app("BL-UNS", "X Y", "XYZPY2222Y", "9811000003", 40, 50000, 5_000_000, 24));
        assertThat(badAmount.status()).isEqualTo(422);
    }

    @Test
    void aaOutageGoesToDeadLetterQueueAndManualRetryRecovers() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, app("BL-UNS", "Meera Shah", "MEEPS9201M", "9811000004", 38, 48000, 150000, 18), "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(states(id, ops).get("DATA").asText()).isEqualTo("PARTIAL");

        JsonNode logs = ok(get("/api/applications/" + id + "/integrations"), ops, null).body();
        long dlq = 0;
        int failedAttempts = 0;
        for (JsonNode l : logs) {
            if ("aa.fetch".equals(l.get("operation").asText()) && "DLQ".equals(l.get("status").asText())) dlq = l.get("id").asLong();
            if ("aa.fetch".equals(l.get("operation").asText()) && !"SUCCESS".equals(l.get("status").asText())) failedAttempts++;
        }
        assertThat(dlq).isPositive();
        assertThat(failedAttempts).isEqualTo(3);

        ok(post("/api/integrations/logs/" + dlq + "/retry"), ops, null);
        assertThat(states(id, ops).get("DATA").asText()).isEqualTo("FETCHED");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(states(id, ops).get("DECISION").asText()).isNotEqualTo("PENDING");
    }

    @Test
    void transientAaFailureIsRetriedAutomatically() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, app("BL-UNS", "Tara Joshi", "TARPJ9150T", "9811000005", 35, 60000, 150000, 18), "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(states(id, ops).get("DATA").asText()).isEqualTo("FETCHED");
    }

    @Test
    void tamperedDocumentLeadsToFraudReject() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, app("PL", "Anil Kumar", "ANIPK1005A", "9811000006", 29, 95000, 400000, 36),
                "PAN", "AADHAAR", "SALARY_SLIP:salary-tamper.pdf");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode st = states(id, ops);
        assertThat(st.get("DECISION").asText()).isEqualTo("REJECT");
        assertThat(st.get("FRAUD").asText()).isEqualTo("BLOCK");
        assertThat(st.get("APP").asText()).isEqualTo("REJECTED");
    }

    @Test
    void referredCaseNeedsFieldVisitAndReasonedOverrideAtL3() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), cm = login("cm1"), cro = login("cro1");
        long id = create(sales, app("BL-UNS", "Suresh Yadav", "SURPY1004S", "9811000007", 45, 95000, 600000, 36), "PAN", "AADHAAR", "UDYAM");
        JsonNode steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        assertThat(steps.toString()).contains("Field visit pending");

        assertThat(call(post("/api/applications/" + id + "/field-visit"), ops, Map.of("note", "short")).status()).isEqualTo(422);
        ok(post("/api/applications/" + id + "/field-visit"), ops, Map.of("note", "Visited depot, three trucks on site"));
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode st = states(id, ops);
        assertThat(st.get("DECISION").asText()).isEqualTo("REFER");
        assertThat(st.get("SANCTION").asText()).isEqualTo("PENDING_L3");

        assertThat(call(post("/api/applications/" + id + "/sanction"), cm, Map.of()).status()).isEqualTo(403);
        Res noReason = call(post("/api/applications/" + id + "/sanction"), cro, Map.of());
        assertThat(noReason.status()).isEqualTo(422);
        assertThat(noReason.body().get("code").asText()).isEqualTo("REASON_REQUIRED");
        ok(post("/api/applications/" + id + "/sanction"), cro, Map.of("note", "Strong GST trend and repeat customer; DPD was a one-off"));
        JsonNode recs = ok(get("/api/applications/" + id + "/sanctions"), cro, null).body();
        assertThat(recs.get(0).get("overrideFlag").asBoolean()).isTrue();
    }

    @Test
    void kycReviewBlocksDecisionUntilResolved() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, app("BL-UNS", "Ravi Gupta", "RAVPG9333R", "9811000008", 42, 70000, 150000, 18), "PAN", "AADHAAR", "UDYAM");
        JsonNode steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        assertThat(states(id, ops).get("KYC").asText()).isEqualTo("REVIEW");
        assertThat(steps.toString()).contains("KYC review pending");
        ok(post("/api/applications/" + id + "/kyc/resolve"), ops, Map.of("verified", true, "note", "Video KYC done, address proof matched"));
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(states(id, ops).get("DECISION").asText()).isNotEqualTo("PENDING");
    }

    @Test
    void rolesAreEnforced() throws Exception {
        String sales = login("sales1");
        assertThat(call(get("/api/admin/users"), sales, null).status()).isEqualTo(403);
        assertThat(call(post("/api/admin/branches"), sales, Map.of("code", "BX", "name", "X", "level", "BRANCH", "parentId", 3)).status()).isEqualTo(403);
        assertThat(mvc.perform(get("/api/applications")).andReturn().getResponse().getStatus()).isEqualTo(401);
        int bad = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"sales1\",\"password\":\"wrong\"}")).andReturn().getResponse().getStatus();
        assertThat(bad).isEqualTo(401);

        String admin = login("admin");
        ok(post("/api/admin/branches"), admin, Map.of("code", "BNGP", "name", "Nagpur Branch", "level", "BRANCH", "parentId", 3, "city", "Nagpur"));
        ok(post("/api/admin/users"), admin, Map.of("username", "co2", "fullName", "New Officer", "role", "CREDIT_OFFICER", "password", "Welcome@123"));
        int newUser = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"co2\",\"password\":\"Welcome@123\"}")).andReturn().getResponse().getStatus();
        assertThat(newUser).isEqualTo(200);
    }
}
