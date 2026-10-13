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

/** Phase 5: model registry. Train a candidate, CRO approves it, the engine uses it, CRO can go back to the built-in model. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=300",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-models"
})
class ModelRegistryIT {

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

    @Test
    void trainApproveUseAndRetire() throws Exception {
        String cm = staff("cm1"), cro = staff("cro1"), ops = staff("ops1"), sales = staff("sales1");
        assertThat(call(post("/api/models/train"), ops, null).status()).isEqualTo(403);
        JsonNode cand = ok(post("/api/models/train"), cm, null).body();
        long id = cand.get("id").asLong();
        assertThat(cand.get("status").asText()).isEqualTo("CANDIDATE");
        assertThat(cand.get("version").asText()).isEqualTo("1.1");
        assertThat(cand.get("dataSource").asText()).isEqualTo("SYNTHETIC");
        assertThat(cand.get("rowsUsed").asInt()).isEqualTo(300);
        @SuppressWarnings("unchecked") List<Map<String, Object>> rows = (List<Map<String, Object>>) SEEN.get(0).get("rows");
        assertThat(rows).hasSize(300);
        assertThat(rows.get(0)).containsKeys("features", "bad", "championPd");
        assertThat((List<?>) SEEN.get(0).get("features")).hasSize(17);

        JsonNode detail = ok(get("/api/models/" + id), cm, null).body();
        assertThat(detail.get("terms").get(0).get("label").asText()).isEqualTo("EMI/cheque bounces (12m)");
        assertThat(detail.get("metrics").get("aucGain").asDouble()).isEqualTo(0.06);

        // the trainer cannot approve; only the CRO can
        assertThat(call(post("/api/models/" + id + "/decision"), cm, Map.of("approve", true, "note", "My own model looks good")).status()).isEqualTo(403);
        assertThat(call(post("/api/models/" + id + "/decision"), cro, Map.of("approve", true, "note", "short")).status()).isEqualTo(422);
        ok(post("/api/models/" + id + "/decision"), cro, Map.of("approve", true, "note", "Better hold-out AUC, stable PSI"));

        long app = application(sales, "Model Person", "MODPP1234M", "9811191001");
        ok(post("/api/applications/" + app + "/process"), ops, null);
        assertThat(ok(get("/api/applications/" + app + "/decision"), ops, null).body().get("modelVersion").asText()).isEqualTo("RHY-PD-TRAINED 1.1");
        assertThat(ok(get("/api/audit"), staff("comp1"), null).body().toString()).contains("model.approved_champion");

        // a second candidate is compared with the trained champion; retiring the champion restores the built-in model
        JsonNode cand2 = ok(post("/api/models/train"), cro, null).body();
        assertThat(cand2.get("version").asText()).isEqualTo("1.2");
        assertThat(call(post("/api/models/" + cand2.get("id").asLong() + "/decision"), cro, Map.of("approve", true, "note", "Approving my own retrain")).body().toString()).contains("MAKER_CHECKER");
        assertThat(call(post("/api/models/champion/retire"), cro, Map.of("note", "Back to built-in for audit")).status()).isEqualTo(204);
        long app2 = application(sales, "Second Person", "SECPP1234S", "9811191002");
        ok(post("/api/applications/" + app2 + "/process"), ops, null);
        assertThat(ok(get("/api/applications/" + app2 + "/decision"), ops, null).body().get("modelVersion").asText()).doesNotContain("TRAINED");
    }
}
