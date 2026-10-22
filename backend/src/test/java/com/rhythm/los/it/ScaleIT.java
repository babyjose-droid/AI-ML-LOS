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

/** Phase 4B: co-lending matcher (pool, reasons, partner file, partner decision) and capital and liquidity planning. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-scale",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class ScaleIT {

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

    String staff(String u) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", u, "password", "Rhythm@123")).body().get("token").asText();
    }

    @Test
    void coLendingPoolFileAndPartnerDecision() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), cm = staff("cm1"), cro = staff("cro1"), co = staff("co1");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", "Colend Person"); m.put("pan", "COLPC1002C"); m.put("mobile", "9878000001");
        m.put("dob", LocalDate.now().minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "3 Bank Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test");
        m.put("bankAccountNo", "5010055443322"); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        JsonNode created = ok(post("/api/applications"), sales, m).body();
        long id = created.get("id").asLong();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        ok(post("/api/applications/" + id + "/process"), ops, null);
        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of());
        String appNo = created.get("appNo").asText();

        JsonNode partners = ok(get("/api/colending/partners"), cm, null).body();
        long retail = 0, msme = 0;
        for (JsonNode p : partners) { if ("PB-RETAIL".equals(p.get("code").asText())) retail = p.get("id").asLong(); if ("PB-MSME".equals(p.get("code").asText())) msme = p.get("id").asLong(); }
        assertThat(call(put("/api/colending/partners/" + retail), cro, Map.of("partnerShare", 0.95, "note", "Agreement addendum 7")).status()).isEqualTo(422);
        assertThat(call(put("/api/colending/partners/" + retail), cm, Map.of("allowedBands", "A,B,C,D,E", "note", "Agreement addendum 7")).status()).isEqualTo(403);
        ok(put("/api/colending/partners/" + retail), cro, Map.of("allowedBands", "A,B,C,D,E", "note", "Agreement addendum 7 widens bands"));

        JsonNode pool = ok(get("/api/colending/pool"), cm, null).body();
        assertThat(pool.get("candidates").asInt()).isEqualTo(1);
        JsonNode retailPool = null, msmePool = null;
        for (JsonNode p : pool.get("partners")) { if (p.get("partner").get("id").asLong() == retail) retailPool = p; if (p.get("partner").get("id").asLong() == msme) msmePool = p; }
        assertThat(retailPool.get("eligibleCount").asInt()).isEqualTo(1);
        JsonNode e = retailPool.get("eligible").get(0);
        assertThat(e.get("appNo").asText()).isEqualTo(appNo);
        assertThat(e.get("partnerAmount").asDouble()).isEqualTo(e.get("amount").asDouble() * 0.8, org.assertj.core.data.Offset.offset(1.0));
        assertThat(e.get("nbfcYield").asDouble()).isGreaterThan(e.get("loanRate").asDouble());
        assertThat(msmePool.get("eligibleCount").asInt()).isZero();
        assertThat(msmePool.get("notEligible").toString()).contains("Product PL not in the partner's programme");

        assertThat(call(post("/api/colending/submit"), cm, Map.of("partnerId", retail, "applicationIds", List.of(id))).status()).isEqualTo(403);
        assertThat(call(post("/api/colending/submit"), ops, Map.of("partnerId", msme, "applicationIds", List.of(id))).status()).isEqualTo(422);
        JsonNode sub = ok(post("/api/colending/submit"), ops, Map.of("partnerId", retail, "applicationIds", List.of(id))).body();
        String batch = sub.get("batch").asText();
        assertThat(batch).startsWith("CL-PB-RETAIL-");
        assertThat(call(post("/api/colending/submit"), ops, Map.of("partnerId", retail, "applicationIds", List.of(id))).status()).isEqualTo(409);
        String file = mvc.perform(get("/api/colending/batches/" + batch + "/file").header("Authorization", "Bearer " + ops)).andReturn().getResponse().getContentAsString();
        assertThat(file).startsWith("Batch,PartnerCode,AppNo").contains(appNo).contains("COLPC1002C").contains(",80,");
        long asg = sub.get("assignments").get(0).get("id").asLong();
        assertThat(call(post("/api/colending/assignments/" + asg + "/decision"), ops, Map.of("approve", false)).status()).isEqualTo(422);
        JsonNode dec = ok(post("/api/colending/assignments/" + asg + "/decision"), ops, Map.of("approve", true, "partnerRef", "PBR-77812")).body();
        assertThat(dec.get("status").asText()).isEqualTo("APPROVED");
        assertThat(ok(get("/api/colending/assignments"), cm, null).body().toString()).contains("PBR-77812").contains("PB-RETAIL");
        JsonNode after = ok(get("/api/colending/pool"), cm, null).body();
        for (JsonNode p : after.get("partners")) if (p.get("partner").get("id").asLong() == retail) assertThat(p.get("eligible").get(0).get("assignment").get("status").asText()).isEqualTo("APPROVED");
        assertThat(call(get("/api/colending/pool"), co, null).status()).isEqualTo(403);
    }

    @Test
    void capitalProjectionAndScenarios() throws Exception {
        String cro = staff("cro1"), comp = staff("comp1"), cm = staff("cm1");
        JsonNode def = ok(get("/api/capital/defaults"), cro, null).body();
        assertThat(def.get("months").asInt()).isEqualTo(12);
        assertThat(def.get("notes").toString()).contains("placeholders");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("ownedFunds", 25e6); in.put("borrowings", 80e6); in.put("cash", 5e6); in.put("book", 100e6); in.put("monthlyDisbursement", 8e6);
        in.put("disbursementGrowthPct", 3); in.put("collectionEfficiency", 0.97); in.put("yieldPct", 20); in.put("costOfFundsPct", 11); in.put("creditLossPct", 2);
        in.put("opexPct", 4); in.put("newBorrowingMonthly", 3e6); in.put("borrowingRepaymentMonthly", 2e6); in.put("avgTenureMonths", 24); in.put("minCrar", 0.15);
        in.put("riskWeight", 1.0); in.put("months", 12);
        JsonNode p = ok(post("/api/capital/project"), comp, in).body();
        assertThat(p.get("months").size()).isEqualTo(12);
        assertThat(p.get("minCash").asDouble()).isNegative();
        assertThat(p.get("alerts").toString()).contains("Cash negative");
        Map<String, Object> bad = new LinkedHashMap<>(in);
        bad.put("ownedFunds", 0);
        assertThat(call(post("/api/capital/project"), comp, bad).status()).isEqualTo(422);
        assertThat(call(post("/api/capital/scenarios"), comp, Map.of("name", "x", "inputs", in)).status()).isEqualTo(403);
        ok(post("/api/capital/scenarios"), cro, Map.of("name", "FY27 plan: 3% monthly growth", "inputs", in));
        assertThat(ok(get("/api/capital/scenarios"), comp, null).body().get(0).get("name").asText()).isEqualTo("FY27 plan: 3% monthly growth");
        assertThat(call(get("/api/capital/defaults"), cm, null).status()).isEqualTo(403);
    }
}
