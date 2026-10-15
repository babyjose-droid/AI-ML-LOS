package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.model.ModelTrainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The lender imports its own loan history; a separate fraud model is trained, approved and used by the engine. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=1600",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-history"
})
class HistoryAndFraudModelIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    static final List<Map<String, Object>> SEEN = new ArrayList<>();

    @TestConfiguration
    static class StubTrainer {
        @Bean
        @Primary
        ModelTrainer stubTrainer() {
            return req -> {
                SEEN.add(req);
                Map<String, Object> score = Map.of("auc", 0.74, "gini", 0.48, "ks", 0.36, "brier", 0.05, "avgPd", 0.06, "badRate", 0.058);
                Map<String, Object> out = new HashMap<>();
                out.put("intercept", -2.9);
                out.put("terms", List.of(Map.of("feature", "bounces", "weight", 0.6, "mean", 0.3, "standardisedWeight", 0.4, "unexpectedSign", false),
                        Map.of("feature", "bureauScoreGap", "weight", 1.0, "mean", 0.0, "standardisedWeight", 0.5, "unexpectedSign", false)));
                out.put("train", score);
                out.put("holdout", score);
                out.put("champion", Map.of("auc", 0.68, "gini", 0.36, "ks", 0.3, "brier", 0.052, "avgPd", 0.07, "badRate", 0.058));
                out.put("aucGain", 0.06);
                out.put("deciles", List.of());
                out.put("psi", 0.01);
                out.put("warnings", List.of());
                out.put("trainRows", 210);
                out.put("holdoutRows", 90);
                return out;
            };
        }
    }

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

    long application(String sales, String name, String pan, String mobile) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", "1990-04-15"); m.put("city", "Pune"); m.put("pincode", "411038"); m.put("businessVintageYears", 5);
        m.put("declaredMonthlyIncome", 80000); m.put("essentialExpenses", 25000); m.put("loanAmount", 200000); m.put("tenureMonths", 24);
        m.put("bankAccountNo", "123456789012"); m.put("bankIfsc", "ICIC0000123"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        long id = ok(post("/api/applications"), sales, m).body().get("id").asLong();
        for (String t : new String[]{"PAN", "AADHAAR", "SALARY_SLIP"}) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        return id;
    }

    static Map<String, Object> row(String ref, String seg, Object maxDpd, String fraud) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", ref); m.put("productCode", "pl"); m.put("segment", seg); m.put("amount", "2,50,000"); m.put("tenureMonths", "24");
        m.put("disbursedOn", "2024-05-10"); m.put("age", "34"); m.put("vintageYears", "6"); m.put("declaredMonthlyIncome", "60000");
        m.put("monthlyCredits", "58000|61000|59500|60200|57800|62000"); m.put("bureauScore", "735"); m.put("activeLoans", "2"); m.put("enquiries6m", "1");
        m.put("maxDpd", maxDpd); m.put("fraud", fraud);
        return m;
    }

    @Test
    void ownHistoryIsImportedAndAFraudModelIsTrainedApprovedAndUsed() throws Exception {
        String cm = staff("cm1"), cro = staff("cro1"), ops = staff("ops1"), sales = staff("sales1");
        Map<String, Object> noOutcome = row("OLD-3", "SALARIED", null, "N");
        Map<String, Object> badSeg = row("OLD-4", "RETAIL", 0, "N");
        Map<String, Object> badScore = row("OLD-5", "MSME", 0, "N");
        badScore.put("bureauScore", "1200");
        assertThat(call(post("/api/models/history"), ops, Map.of("rows", List.of(row("OLD-1", "SALARIED", 0, "N")))).status()).isEqualTo(403);
        JsonNode imp = ok(post("/api/models/history"), cm, Map.of("rows", List.of(row("OLD-1", "SALARIED", 0, "N"), row("OLD-2", "MSME", 120, "Y"), noOutcome, badSeg, badScore))).body();
        assertThat(imp.get("added").asInt()).isEqualTo(2);
        assertThat(imp.get("bad").asInt()).isEqualTo(1);
        assertThat(imp.get("fraud").asInt()).isEqualTo(1);
        assertThat(imp.get("errors").toString()).contains("Row 3: the outcome is missing").contains("Row 4: segment").contains("Row 5: bureauScore");
        // importing the same reference again updates it; synthetic references cannot be overwritten
        assertThat(ok(post("/api/models/history"), cm, Map.of("rows", List.of(row("OLD-1", "SALARIED", 95, "N")))).body().get("updated").asInt()).isEqualTo(1);
        assertThat(ok(post("/api/models/history"), cm, Map.of("rows", List.of(row("HIST-1", "SALARIED", 0, "N")))).body().get("errors").toString()).contains("synthetic");
        JsonNode sum = ok(get("/api/models/history"), cm, null).body();
        assertThat(sum.get("bySource").toString()).contains("\"source\":\"IMPORT\",\"loans\":2,\"bad\":2,\"fraud\":1");
        assertThat(sum.get("columns").toString()).contains("monthlyCredits").contains("writtenOff");

        // two own loans are not enough to train on their own
        assertThat(call(post("/api/models/train"), cm, Map.of("onlyOwn", true)).body().get("code").asText()).isEqualTo("NOT_ENOUGH_DATA");

        // the fraud model: its own features and labels, the same approval
        JsonNode fr = ok(post("/api/models/train-fraud"), cm, null).body();
        JsonNode cand = fr.get("candidate");
        assertThat(cand.get("kind").asText()).isEqualTo("FRAUD");
        assertThat(cand.get("name").asText()).isEqualTo("RHY-FRAUD-TRAINED");
        assertThat(fr.get("frauds").asInt()).isGreaterThanOrEqualTo(20);
        @SuppressWarnings("unchecked") List<String> feats = (List<String>) SEEN.get(SEEN.size() - 1).get("features");
        assertThat(feats).containsExactlyElementsOf(com.rhythm.los.decision.engine.FraudFeatures.ALL);
        assertThat(ok(get("/api/models/" + cand.get("id").asLong()), cm, null).body().get("terms").size()).isPositive();
        assertThat(ok(get("/api/models/" + cand.get("id").asLong() + "/monitoring"), cm, null).body().get("kind").asText()).isEqualTo("FRAUD");
        ok(post("/api/models/" + cand.get("id").asLong() + "/decision"), cro, Map.of("approve", true, "note", "Fraud model beats the fixed weights"));

        long app = application(sales, "Fraud Model Person", "FRMPP1234F", "9811191101");
        ok(post("/api/applications/" + app + "/process"), ops, null);
        assertThat(ok(get("/api/applications/" + app + "/decision"), ops, null).body().get("stepsJson").asText()).contains("RHY-FRAUD-TRAINED 1.1");
        assertThat(call(post("/api/models/champion/retire"), cro, Map.of("note", "Back to the built-in scores")).status()).isEqualTo(204);
    }
}
