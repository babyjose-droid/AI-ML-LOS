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

/** Phase 5: borrower self-service portal. Mobile OTP sign-in, own applications only, and nothing from the staff API. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-borrower"
})
class BorrowerPortalIT {

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

    @Test
    void borrowerAppliesUploadsFollowsAndAcceptsKfs() throws Exception {
        // sign-in
        assertThat(call(post("/api/borrower/otp"), null, Map.of("mobile", "12345")).status()).isEqualTo(422);
        ok(post("/api/borrower/otp"), null, Map.of("mobile", "+91 98470 00001"));
        assertThat(call(post("/api/borrower/otp/verify"), null, Map.of("mobile", "9847000001", "otp", "111111")).body().toString()).contains("BAD_OTP");
        String b1 = ok(post("/api/borrower/otp/verify"), null, Map.of("mobile", "9847000001", "otp", "123456")).body().get("token").asText();
        assertThat(call(post("/api/borrower/otp/verify"), null, Map.of("mobile", "9847000001", "otp", "123456")).body().toString()).contains("OTP_EXPIRED");

        // a borrower token reaches nothing in the staff API
        assertThat(call(get("/api/applications"), b1, null).status()).isEqualTo(403);
        assertThat(call(get("/api/my-work"), b1, null).status()).isEqualTo(403);
        assertThat(call(get("/api/policies/live"), b1, null).status()).isEqualTo(403);
        assertThat(call(get("/api/borrower/applications"), staff("ops1"), null).status()).isEqualTo(403);
        assertThat(call(get("/api/borrower/applications"), null, null).status()).isEqualTo(401);

        assertThat(ok(get("/api/borrower/products"), null, null).body().toString()).contains("Personal loan");
        Map<String, Object> noConsent = application("Priya Nair", "PRIPN1002P");
        noConsent.put("consentAa", false);
        assertThat(call(post("/api/borrower/applications"), b1, noConsent).body().toString()).contains("CONSENT_MISSING");
        JsonNode v = ok(post("/api/borrower/applications"), b1, application("Priya Nair", "PRIPN1002P")).body();
        long id = v.get("id").asLong();
        assertThat(v.get("stage").get("label").asText()).isEqualTo("Application received");
        assertThat(v.toString()).doesNotContain("\"pd\"").doesNotContain("reasonCodes");

        // staff see it, made through the portal with the borrower's own mobile
        String ops = staff("ops1");
        JsonNode staffView = ok(get("/api/applications/" + id), ops, null).body().get("application");
        assertThat(staffView.get("channel").asText()).isEqualTo("BORROWER_PORTAL");
        assertThat(staffView.get("mobile").asText()).isEqualTo("9847000001");
        assertThat(staffView.get("appState").asText()).isEqualTo("SUBMITTED");

        // another borrower cannot see it
        String b2 = borrower("9847000002");
        assertThat(call(get("/api/borrower/applications/" + id), b2, null).status()).isEqualTo(404);
        assertThat(ok(get("/api/borrower/applications"), b2, null).body().size()).isEqualTo(0);

        for (String t : new String[]{"PAN", "AADHAAR", "SALARY_SLIP"}) {
            MvcResult r = mvc.perform(multipart("/api/borrower/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + b1)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
        assertThat(mvc.perform(multipart("/api/borrower/applications/" + id + "/documents")
                .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "%PDF".getBytes())).param("type", "PAN")
                .header("Authorization", "Bearer " + b2)).andReturn().getResponse().getStatus()).isEqualTo(404);

        // operations and credit do their part
        ok(post("/api/applications/" + id + "/process"), ops, null);
        ok(post("/api/applications/" + id + "/sanction"), staff("cm1"), Map.of());
        JsonNode sanctioned = ok(get("/api/borrower/applications/" + id), b1, null).body();
        assertThat(sanctioned.get("stage").get("label").asText()).isEqualTo("Loan approved");
        assertThat(sanctioned.get("canAcceptKfs").asBoolean()).isTrue();
        assertThat(sanctioned.get("kfs").get("aprPa").asDouble()).isGreaterThan(0);

        assertThat(call(post("/api/borrower/applications/" + id + "/kfs/accept"), b1, Map.of("otp", "000000")).status()).isEqualTo(422);
        JsonNode accepted = ok(post("/api/borrower/applications/" + id + "/kfs/accept"), b1, Map.of("otp", "123456")).body();
        assertThat(accepted.get("stage").get("label").asText()).isEqualTo("Terms accepted");
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("DISB").asText()).isEqualTo("READY");
        assertThat(ok(get("/api/audit?applicationId=" + id), staff("comp1"), null).body().toString()).contains("borrower:9847000001");
    }

    @Test
    void otpRequestsAreRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) ok(post("/api/borrower/otp"), null, Map.of("mobile", "9847000009"));
        assertThat(call(post("/api/borrower/otp"), null, Map.of("mobile", "9847000009")).status()).isEqualTo(429);
    }
}
