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

/** Phase 3C: compliance pack, regulatory returns (classification, NPA movement, exposure, CIC file), branch and officer scores, policy assistant. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=300",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-compliance",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class ComplianceIT {

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

    static Map<String, Object> row(String ref, double outstanding, double overdue, int dpd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanRef", ref); m.put("outstanding", outstanding); m.put("overdue", overdue); m.put("dpd", dpd);
        return m;
    }

    JsonNode loan(String token, String ref) throws Exception {
        JsonNode l = ok(get("/api/book/loans?q=" + ref), token, null).body();
        assertThat(l.size()).as(ref).isEqualTo(1);
        return l.get(0);
    }

    static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    Map<String, Object> newLoan(String ref, String segment, double outstanding, double overdue, int dpd) {
        Map<String, Object> r = row(ref, outstanding, overdue, dpd);
        r.put("borrowerName", "Borrower " + ref); r.put("segment", segment); r.put("productCode", "BL");
        r.put("sanctionedAmount", 300000); r.put("disbursedOn", LocalDate.now().minusMonths(10).toString()); r.put("tenureMonths", 36); r.put("rate", 20);
        return r;
    }

    void feed(String ops, LocalDate on, Object... rows) throws Exception {
        JsonNode f = ok(post("/api/book/feed"), ops, Map.of("feedDate", on.toString(), "rows", List.of(rows))).body();
        assertThat(f.get("rejected").asInt()).as(String.valueOf(f)).isZero();
    }

    JsonNode disbursed(String sales, String ops, String cm, String name, String pan, String mobile, String account) throws Exception {
        LocalDate today = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", today.minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "11 Rule Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test");
        m.put("bankAccountNo", account); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        JsonNode created = ok(post("/api/applications"), sales, m).body();
        long app = created.get("id").asLong();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + app + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        ok(post("/api/applications/" + app + "/process"), ops, null);
        ok(post("/api/applications/" + app + "/sanction"), cm, Map.of());
        ok(post("/api/applications/" + app + "/kfs/accept"), sales, Map.of("otp", "123456"));
        ok(post("/api/applications/" + app + "/disburse"), ops, null);
        return created;
    }

    static Map<String, JsonNode> items(JsonNode pack) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (JsonNode i : pack.get("items")) m.put(i.get("id").asText(), i);
        return m;
    }

    @Test
    void compliancePackRegulatoryReturnsScoresAndAssistant() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), cm = staff("cm1"), cro = staff("cro1"), comp = staff("comp1"), co = staff("co1");
        LocalDate d0 = LocalDate.now();
        JsonNode created = disbursed(sales, ops, cm, "Compliance Person", "COMPC1002C", "9888000001", "5010077665544");
        String no = created.get("appNo").asText();

        // book: an NPA, an SMA loan that becomes NPA and then cures, a restructure, a contact
        feed(ops, d0, newLoan("NP-0001", "MSME", 120000, 40000, 120), newLoan("MV-0001", "MSME", 200000, 9000, 25));
        feed(ops, d0.plusMonths(1), row("MV-0001", 198000, 30000, 95));
        feed(ops, d0.plusMonths(2), row("MV-0001", 170000, 0, 0));
        long np = loan(cro, "NP-0001").get("id").asLong();
        long pid = ok(post("/api/book/loans/" + np + "/restructure"), cm, Map.of("kind", "TENURE_EXTENSION", "newTenureMonths", 40, "reason", "Seasonal loss")).body().get("id").asLong();
        ok(post("/api/restructures/" + pid + "/approve"), cro, Map.of());
        String morning = LocalDate.now(IST).minusDays(1).atTime(10, 30).atZone(IST).toOffsetDateTime().toString();
        ok(post("/api/collections/loans/" + np + "/contacts"), ops, Map.of("channel", "CALL", "outcome", "NO_ANSWER", "contactedAt", morning));

        // compliance pack: evidence-based checks
        JsonNode pack = ok(get("/api/compliance/pack"), comp, null).body();
        Map<String, JsonNode> it = items(pack);
        assertThat(it).hasSize(15);
        String all = pack.get("items").toString();
        for (String id : List.of("C01", "C02", "C03", "C05", "C06", "C08", "C09", "C10", "C13"))
            assertThat(it.get(id).get("status").asText()).as(id + " in " + all).isEqualTo("PASS");
        assertThat(it.get("C01").get("checked").asInt()).isEqualTo(1);
        assertThat(it.get("C15").get("status").asText()).isEqualTo("EXCEPTION");
        assertThat(it.get("C15").get("samples").toString()).contains("NP-0001").doesNotContain("LN-");
        assertThat(it.get("C14").get("status").asText()).isEqualTo("NOT_APPLICABLE");
        MvcResult csv = mvc.perform(get("/api/compliance/pack.csv").header("Authorization", "Bearer " + comp)).andReturn();
        assertThat(csv.getResponse().getContentAsString()).startsWith("id,area,requirement").contains("C13");
        assertThat(call(get("/api/compliance/pack"), sales, null).status()).isEqualTo(403);

        // asset classification with NPA age classes
        JsonNode ac = ok(get("/api/regulatory/asset-classification"), cro, null).body();
        assertThat(ac.get("npaByAge").get(0).get("class").asText()).isEqualTo("SUB-STANDARD");
        assertThat(ac.get("npaByAge").get(0).get("loans").asInt()).isGreaterThanOrEqualTo(1);

        // NPA movement: MV-0001 added in month 1, upgraded in month 2
        JsonNode m1 = ok(get("/api/regulatory/npa-movement?from=" + d0.plusDays(1) + "&to=" + d0.plusMonths(1)), cro, null).body();
        assertThat(m1.get("additions").get("loans").asInt()).isEqualTo(1);
        assertThat(m1.get("opening").get("loans").asInt()).isEqualTo(1);
        JsonNode m2 = ok(get("/api/regulatory/npa-movement?from=" + d0.plusMonths(1).plusDays(1) + "&to=" + d0.plusMonths(2)), cro, null).body();
        assertThat(m2.get("upgradations").get("loans").asInt()).isEqualTo(1);
        assertThat(m2.get("upgradations").get("amount").asLong()).isEqualTo(198000);
        assertThat(call(get("/api/regulatory/npa-movement?from=" + d0 + "&to=" + d0.minusDays(3)), cro, null).status()).isEqualTo(422);

        // exposure
        JsonNode ex = ok(get("/api/regulatory/exposure"), cro, null).body();
        assertThat(ex.get("bySegment").toString()).contains("MSME");
        assertThat(ex.get("byRegion").toString()).contains("West (Maharashtra, MP, Chhattisgarh)");
        assertThat(ex.get("top20Share").asDouble()).isEqualTo(1.0);

        // CIC file: LMS loans without PAN are flagged; the full file only for compliance
        JsonNode cic = ok(get("/api/regulatory/cic"), cm, null).body();
        assertThat(cic.get("missingPan").asInt()).isEqualTo(2);
        assertThat(cic.get("ready").asBoolean()).isFalse();
        assertThat(call(get("/api/regulatory/cic.csv"), cm, null).status()).isEqualTo(403);
        String file = mvc.perform(get("/api/regulatory/cic.csv").header("Authorization", "Bearer " + comp)).andReturn().getResponse().getContentAsString();
        assertThat(file).startsWith("MemberReference,AccountNumber").contains("COMPC1002C").contains("SUB-STANDARD");

        // scores
        JsonNode br = ok(get("/api/scores/branches"), cro, null).body();
        assertThat(br.size()).isGreaterThanOrEqualTo(1);
        assertThat(br.get(0).get("score").asInt()).isBetween(0, 100);
        assertThat(br.get(0).has("parts")).isTrue();
        JsonNode of = ok(get("/api/scores/officers"), cro, null).body();
        assertThat(of.toString()).contains("sales1");
        assertThat(call(get("/api/scores/officers"), sales, null).status()).isEqualTo(403);

        // policy assistant
        JsonNode a1 = ok(post("/api/assistant/ask"), co, Map.of("question", "Who can sanction a loan of 8 lakh?")).body();
        assertThat(a1.get("mode").asText()).isEqualTo("extractive");
        assertThat(a1.get("sources").toString()).contains("DEL-matrix");
        JsonNode a2 = ok(post("/api/assistant/ask"), co, Map.of("question", "What does RC-023 mean?")).body();
        assertThat(a2.get("answer").asText()).contains("Money rotation");
        JsonNode a3 = ok(post("/api/assistant/ask"), co, Map.of("question", "Why did " + no + " for Compliance Person (COMPC1002C, 9888000001) need L2?")).body();
        assertThat(a3.get("application").asText()).isEqualTo(no);
        assertThat(a3.get("question").asText()).doesNotContain("Compliance Person").doesNotContain("COMPC1002C").doesNotContain("9888000001").contains("[name]");
        assertThat(a3.get("sources").get(0).get("id").asText()).isEqualTo(no);
        assertThat(a3.get("sources").get(0).get("text").asText()).contains("Sanction authority L2").doesNotContain("Compliance Person");
        assertThat(call(post("/api/assistant/ask"), co, Map.of("question", "")).status()).isEqualTo(422);
        assertThat(call(post("/api/assistant/ask"), co, Map.of("question", "Explain APP-999999")).status()).isEqualTo(404);
    }
}
