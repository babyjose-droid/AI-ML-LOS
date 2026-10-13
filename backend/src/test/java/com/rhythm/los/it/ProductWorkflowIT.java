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

/** Phase 5: workflow settings per product: video KYC requirement, bank data source and auto-sanction. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-workflow"
})
class ProductWorkflowIT {

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

    String staff(String user) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", user, "password", "Rhythm@123")).body().get("token").asText();
    }

    String borrower(String mobile) throws Exception {
        ok(post("/api/borrower/otp"), null, Map.of("mobile", mobile));
        return ok(post("/api/borrower/otp/verify"), null, Map.of("mobile", mobile, "otp", "123456")).body().get("token").asText();
    }

    Map<String, Object> application(String name, String pan) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", "9000000000"); m.put("gender", "Prefer not to say");
        m.put("dob", "1992-03-02"); m.put("city", "Kochi"); m.put("pincode", "682020"); m.put("address", "4 Lake View");
        m.put("businessName", "Coastal Systems"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Home renovation");
        m.put("bankAccountNo", "123456789012"); m.put("bankIfsc", "HDFC0001234");
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

    Map<String, Object> product(String admin, String code) throws Exception {
        return Json.MAPPER.convertValue(ok(get("/api/products/" + code), admin, null).body(), Map.class);
    }

    @Test
    void videoKycRequiredByProductBlocksTheDecision() throws Exception {
        String admin = staff("admin"), sales = staff("sales1"), ops = staff("ops1");
        Map<String, Object> pl = product(admin, "PL");
        pl.put("videoKycRule", "ALWAYS");
        ok(put("/api/admin/products/PL"), admin, pl);

        Map<String, Object> a = application("Deepa Menon", "DEEPM4321D");
        a.put("mobile", "9811180001");
        a.put("declaredMonthlyIncome", 80000); a.put("essentialExpenses", 25000); a.put("loanAmount", 200000); a.put("dob", "1990-04-15");
        long id = ok(post("/api/applications"), sales, a).body().get("id").asLong();
        upload(id, sales, "PAN", "AADHAAR", "SALARY_SLIP");
        String steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps").toString();
        assertThat(steps).contains("Video KYC required for this product");
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("DECISION").asText()).isEqualTo("PENDING");

        pl.put("videoKycRule", "ON_KYC_REVIEW");
        ok(put("/api/admin/products/PL"), admin, pl);
        steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps").toString();
        assertThat(steps).contains("Decision engine");
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("DECISION").asText()).isNotEqualTo("PENDING");
    }

    @Test
    void cleanSmallLoanIsAutoSanctionedWhenTheProductAllowsIt() throws Exception {
        String admin = staff("admin"), sales = staff("sales1"), ops = staff("ops1");
        Map<String, Object> pl = product(admin, "PL");
        pl.put("autoSanctionLimit", 600000);
        assertThat(call(put("/api/admin/products/PL"), admin, Map.of()).status()).isEqualTo(400);
        Map<String, Object> bad = new java.util.LinkedHashMap<>(pl);
        bad.put("autoSanctionLimit", 9_000_000);
        assertThat(call(put("/api/admin/products/PL"), admin, bad).body().toString()).contains("BAD_AUTO_SANCTION");
        ok(put("/api/admin/products/PL"), admin, pl);

        Map<String, Object> a = application("Arjun Pillai", "ARJPP4321A");
        a.put("mobile", "9811180002");
        a.put("declaredMonthlyIncome", 80000); a.put("essentialExpenses", 25000); a.put("loanAmount", 200000); a.put("dob", "1990-04-15");
        long id = ok(post("/api/applications"), sales, a).body().get("id").asLong();
        upload(id, sales, "PAN", "AADHAAR", "SALARY_SLIP");
        String steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps").toString();
        JsonNode d = ok(get("/api/applications/" + id + "/decision"), ops, null).body();
        JsonNode st = ok(get("/api/applications/" + id), ops, null).body().get("states");
        if ("APPROVE".equals(d.get("decision").asText()) && "L1".equals(d.path("delegationLevel").asText())) {
            assertThat(steps).contains("Auto-sanctioned");
            assertThat(st.get("SANCTION").asText()).isEqualTo("SANCTIONED");
            assertThat(ok(get("/api/applications/" + id + "/sanctions"), ops, null).body().toString()).contains("\"level\":\"AUTO\"");
        } else {
            assertThat(steps).doesNotContain("Auto-sanctioned");
            assertThat(st.get("SANCTION").asText()).startsWith("PENDING_");
        }
        // never auto-sanctioned above the limit
        pl.put("autoSanctionLimit", 100000);
        ok(put("/api/admin/products/PL"), admin, pl);
        Map<String, Object> b = application("Neha Kapoor", "NEHPK4321N");
        b.put("mobile", "9811180003");
        b.put("declaredMonthlyIncome", 80000); b.put("essentialExpenses", 25000); b.put("loanAmount", 200000); b.put("dob", "1990-04-15");
        long id2 = ok(post("/api/applications"), sales, b).body().get("id").asLong();
        upload(id2, sales, "PAN", "AADHAAR", "SALARY_SLIP");
        assertThat(ok(post("/api/applications/" + id2 + "/process"), ops, null).body().get("steps").toString()).doesNotContain("Auto-sanctioned");

        Map<String, Object> bl = product(admin, "BL-UNS");
        bl.put("bankDataSource", "STATEMENT_ONLY");
        ok(put("/api/admin/products/BL-UNS"), admin, bl);
        assertThat(product(admin, "BL-UNS").get("requiredDocs").toString()).contains("BANK_STATEMENT");
    }
}
