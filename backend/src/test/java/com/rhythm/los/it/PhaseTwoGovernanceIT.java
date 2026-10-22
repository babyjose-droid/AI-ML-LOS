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

/** Phase 2 governance: shadow scoring and drift (PSI), fairness monitoring, borrower-language sanction letter and KFS. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=1600",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-governance",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class PhaseTwoGovernanceIT {

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

    long application(String sales, String name, String pan, String mobile, String gender) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", java.time.LocalDate.now().minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "3 Gov Road");
        m.put("pincode", "422001"); m.put("gender", gender); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test");
        m.put("bankAccountNo", "5010012345678"); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true);
        m.put("consentKyc", true); m.put("preferredLanguage", "mr");
        long id = ok(post("/api/applications"), sales, m).body().get("id").asLong();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        return id;
    }

    @Test
    void candidatesScoreInShadowAndDriftIsMeasured() throws Exception {
        String cm = staff("cm1"), sales = staff("sales1"), ops = staff("ops1"), comp = staff("comp1");
        JsonNode trained = ok(post("/api/models/train"), cm, null).body();
        long id = application(sales, "Shadow Person", "SHAPS1002S", "9855000001", "FEMALE");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        String kind = ok(get("/api/applications/" + id + "/decision"), ops, null).body().get("modelVersion").asText().contains("THICK") ? "THICK" : "NTC";
        long thick = 0;
        for (JsonNode c : trained.get("candidates")) if (kind.equals(c.get("kind").asText())) thick = c.get("id").asLong();
        assertThat(thick).as("candidate for " + kind).isPositive();

        JsonNode mon = ok(get("/api/models/" + thick + "/monitoring"), comp, null).body();
        assertThat(mon.get("reference").asText()).isEqualTo("training data");
        assertThat(mon.get("shadow").get("scored").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(mon.get("shadow").has("bandAgreement")).isTrue();
        assertThat(mon.get("psi").size()).isGreaterThan(5);
        assertThat(mon.get("psi").toString()).contains("Predicted PD").contains("TOO_FEW");
        JsonNode builtIn = ok(get("/api/models/monitoring/built-in/NTC"), comp, null).body();
        assertThat(builtIn.get("reference").asText()).isEqualTo("loan book");
        assertThat(call(get("/api/models/monitoring/built-in/XYZ"), comp, null).status()).isEqualTo(422);
        assertThat(call(get("/api/models/" + thick + "/monitoring"), sales, null).status()).isEqualTo(403);

        // fairness: the decision appears in each dimension
        JsonNode f = ok(get("/api/fairness?days=30"), comp, null).body();
        assertThat(f.get("decisions").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(f.get("dimensions").size()).isEqualTo(4);
        assertThat(f.get("dimensions").toString()).contains("Female").contains("West (Maharashtra, MP, Chhattisgarh)").contains("TOO_FEW");
        assertThat(f.get("modelInputs").toString()).doesNotContain("Gender");
        assertThat(call(get("/api/fairness"), sales, null).status()).isEqualTo(403);
    }

    @Test
    void sanctionLetterInTheBorrowersLanguageAndKfsAcceptanceLanguage() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), cm = staff("cm1");
        long id = application(sales, "Letter Person", "LETPP1002L", "9855000002", "MALE");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(call(get("/api/applications/" + id + "/sanction-letter"), ops, null).status()).isEqualTo(409);
        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of("conditions", List.of("Original salary slips at disbursement")));
        JsonNode l = ok(get("/api/applications/" + id + "/sanction-letter"), ops, null).body();
        assertThat(l.get("language").asText()).isEqualTo("mr");
        assertThat(l.get("kfs").get("aprPa").asDouble()).isGreaterThan(l.get("kfs").get("ratePa").asDouble());
        assertThat(l.get("conditions").toString()).contains("Original salary slips at disbursement");
        assertThat(l.get("approver").asText()).isEqualTo("cm1");
        assertThat(l.has("acceptedAt")).isFalse();
        assertThat(ok(get("/api/applications/" + id + "/sanction-letter?lang=ta"), ops, null).body().get("language").asText()).isEqualTo("ta");
        assertThat(ok(get("/api/applications/" + id + "/sanction-letter?lang=xx"), ops, null).body().get("language").asText()).isEqualTo("mr");

        ok(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "123456", "language", "ta"));
        JsonNode after = ok(get("/api/applications/" + id + "/sanction-letter"), ops, null).body();
        assertThat(after.get("acceptedLanguage").asText()).isEqualTo("ta");
        assertThat(after.has("acceptedAt")).isTrue();
    }
}
