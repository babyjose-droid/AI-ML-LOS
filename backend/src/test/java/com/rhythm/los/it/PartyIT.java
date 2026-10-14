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

/** Co-applicants and guarantors: product rules, consent by OTP and invite link, joint underwriting, gates and signatures. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents"
})
class PartyIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    String login(String user) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"Rhythm@123\"}")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return Json.MAPPER.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    record Res(int status, JsonNode body) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
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

    Map<String, Object> app(String product, String name, String pan, String mobile, double amount, int tenure) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", product); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(40).toString()); m.put("city", "Nashik"); m.put("address", "1 Test Road"); m.put("pincode", "422001");
        m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 95000); m.put("essentialExpenses", 30000);
        m.put("loanAmount", amount); m.put("tenureMonths", tenure); m.put("purpose", "Test");
        m.put("bankAccountNo", "5010012345678"); m.put("bankIfsc", "HDFC0001234");
        m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        return m;
    }

    long create(String token, Map<String, Object> body, String... docs) throws Exception {
        long id = ok(post("/api/applications"), token, body).body().get("id").asLong();
        for (String d : docs) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", d.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", d).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        return id;
    }

    Map<String, Object> party(String role, String rel, String name, String pan, String mobile, int age, Integer income, boolean counted) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role); m.put("relationship", rel); m.put("name", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(age).toString()); m.put("city", "Nashik");
        m.put("monthlyIncome", income); m.put("incomeConsidered", counted);
        return m;
    }

    JsonNode states(long id, String token) throws Exception { return ok(get("/api/applications/" + id), token, null).body().get("states"); }

    @Test
    void jointLoanFromRulesToDisbursement() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), field = login("field1"), co = login("co1");
        // business loan: a guarantor is required above 5 lakh, spouse income may be counted
        JsonNode rules = ok(get("/api/products/BL-UNS/party-rules"), sales, null).body();
        assertThat(rules.get("guarantor").get("requiredWhen").asText()).isEqualTo("amount > 500000");

        long id = create(sales, app("BL-UNS", "Joint Applicant", "JNTPA4321J", "9855000001", 600_000, 36), "PAN", "AADHAAR", "UDYAM");
        // the rule is shown before anyone is added, and blocks sanction, not submission
        JsonNode view = ok(get("/api/applications/" + id + "/parties"), sales, null).body();
        assertThat(view.get("issues").toString()).contains("A guarantor is required before sanction");

        // refused: the applicant's own mobile, a parent's income on a business loan, a guarantor's income
        assertThat(call(post("/api/applications/" + id + "/parties"), sales, party("CO_APPLICANT", "SPOUSE", "Asha Joint", "JNTPB4321K", "9855000001", 38, 40000, true)).body().get("code").asText())
                .isEqualTo("SAME_MOBILE");
        assertThat(call(post("/api/applications/" + id + "/parties"), sales, party("CO_APPLICANT", "PARENT", "Old Joint", "JNTPD4321D", "9855000009", 62, 30000, true)).body().toString())
                .contains("cannot be added");
        assertThat(call(post("/api/applications/" + id + "/parties"), sales, party("GUARANTOR", "FRIEND", "Friend Joint", "JNTPE4321E", "9855000008", 45, 50000, true)).body().toString())
                .contains("guarantor's income is not counted");

        long asha = ok(post("/api/applications/" + id + "/parties"), sales, party("CO_APPLICANT", "SPOUSE", "Asha Joint", "JNTPB4321K", "9855000002", 38, 40000, true)).body().get("id").asLong();
        // PAN 9050: the mock bureau has no record for this guarantor
        long ravi = ok(post("/api/applications/" + id + "/parties"), sales, party("GUARANTOR", "PARENT", "Ravi Joint", "JNTPC9050L", "9855000003", 55, null, false)).body().get("id").asLong();
        assertThat(call(post("/api/applications/" + id + "/parties"), sales, party("GUARANTOR", "SIBLING", "Two Joint", "JNTPF4321F", "9855000004", 40, null, false)).body().toString())
                .contains("at most 1 guarantor");

        // submitted, but the decision waits for both parties' consent
        JsonNode steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        assertThat(steps.toString()).contains("has not given consent yet");
        assertThat(ok(get("/api/my-work"), sales, null).body().toString()).contains("Asha Joint (co-applicant): get consent");

        // co-applicant consents in the branch with the OTP sent to her own phone
        ok(post("/api/parties/" + asha + "/otp"), sales, null);
        assertThat(call(post("/api/parties/" + asha + "/consent"), sales, Map.of("otp", "000000", "consentKyc", true, "consentBureau", true)).body().get("code").asText()).isEqualTo("BAD_OTP");
        assertThat(ok(post("/api/parties/" + asha + "/consent"), sales, Map.of("otp", "123456", "consentKyc", true, "consentBureau", true, "language", "mr"))
                .body().get("status").asText()).isEqualTo("CONSENTED");

        // guarantor through the invite link on his own phone, without staff sign-in
        String path = ok(post("/api/parties/" + ravi + "/invite"), sales, null).body().get("path").asText();
        String token = path.substring("/party/".length()).replaceAll("\\?.*$", "");
        JsonNode pub = ok(get("/api/party/c/" + token), null, null).body();
        assertThat(pub.get("role").asText()).isEqualTo("GUARANTOR");
        assertThat(pub.get("applicant").asText()).isEqualTo("Joint");
        assertThat(pub.toString()).doesNotContain("JNTPA4321J").doesNotContain("9855000001");
        assertThat(call(get("/api/party/c/not-a-real-token-123456789"), null, null).status()).isEqualTo(404);
        ok(post("/api/party/c/" + token + "/otp"), null, null);
        String session = ok(post("/api/party/c/" + token + "/verify"), null, Map.of("otp", "123456")).body().get("session").asText();
        assertThat(call(post("/api/party/c/" + token + "/consent").header("X-Party-Session", "wrong"), null,
                Map.of("consentKyc", true, "consentBureau", true)).status()).isEqualTo(401);
        ok(post("/api/party/c/" + token + "/consent").header("X-Party-Session", session), null, Map.of("consentKyc", true, "consentBureau", true));
        assertThat(ok(get("/api/applications/" + id + "/consents"), ops, null).body().toString()).contains("PARTY_KYC").contains("PARTY_BUREAU");

        // KYC and bureau for each party, then the decision with the spouse's income and the guarantor policy
        steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        assertThat(steps.toString()).contains("Asha Joint: KYC verified").contains("Ravi Joint: bureau no hit");
        ok(post("/api/applications/" + id + "/field-visit"), field, Map.of("note", "Visited depot, three trucks on site"));
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode d = ok(get("/api/applications/" + id + "/decision"), ops, null).body();
        assertThat(d.get("stepsJson").asText()).contains("Co-applicants & guarantors").contains("40,000/mo income from 1 added");
        assertThat(d.get("rulesJson").asText()).contains("R23").contains("Ravi Joint (guarantor): no credit record");
        assertThat(d.get("reasonCodes").asText()).contains("RC-026");
        assertThat(d.get("creditMemo").asText()).contains("Joint: 1 co-applicant(s) and 1 guarantor(s)");

        // removing the guarantor would bring back the rule; parties added after the decision need a re-run
        String level = states(id, ops).get("SANCTION").asText().replace("PENDING_", "");
        String approver = login(Map.of("L1", "co1", "L2", "cm1", "L3", "cro1").get(level));
        long late = ok(post("/api/applications/" + id + "/parties"), co, party("CO_APPLICANT", "BUSINESS_PARTNER", "Late Joint", "JNTPG4321G", "9855000005", 41, null, false)).body().get("id").asLong();
        Res blocked = call(post("/api/applications/" + id + "/sanction"), approver, Map.of("note", "Guarantor known to the branch for years"));
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.body().get("code").asText()).isEqualTo("PARTIES_NOT_READY");
        assertThat(blocked.body().toString()).contains("Late Joint (co-applicant) has not given consent yet");
        ok(post("/api/parties/" + late + "/remove"), sales, Map.of("note", "Partner will not join the loan"));
        assertThat(call(post("/api/parties/" + ravi + "/remove"), sales, Map.of("note", "short")).status()).isEqualTo(422);

        ok(post("/api/applications/" + id + "/sanction"), approver, Map.of("note", "Guarantor known to the branch for years"));
        // parties cannot change after sanction; the borrower accepts the KFS, then every party signs
        assertThat(call(post("/api/parties/" + ravi + "/remove"), sales, Map.of("note", "Trying to remove after sanction")).status()).isEqualTo(409);
        ok(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "123456"));
        Res early = call(post("/api/applications/" + id + "/disburse"), ops, null);
        assertThat(early.status()).isEqualTo(409);
        assertThat(early.body().toString()).contains("Asha Joint (co-applicant) has not signed").contains("Ravi Joint (guarantor) has not signed the guarantee");
        ok(post("/api/parties/" + asha + "/otp"), sales, null);
        ok(post("/api/parties/" + asha + "/sign"), sales, Map.of("otp", "123456"));
        ok(post("/api/party/c/" + token + "/sign").header("X-Party-Session", session), null, null);
        JsonNode after = ok(get("/api/applications/" + id + "/parties"), ops, null).body();
        assertThat(after.get("parties").toString()).contains("GUARANTEE_DEED").contains("KFS_AND_AGREEMENT");
        ok(post("/api/applications/" + id + "/disburse"), ops, null);
        assertThat(states(id, ops).get("APP").asText()).isEqualTo("DISBURSED");
        assertThat(ok(get("/api/audit?applicationId=" + id), login("comp1"), null).body().toString())
                .contains("party.consented").contains("party.signed").contains("party.kyc");
    }

    @Test
    void creditCanAskForAPartyAndAManagerCanWaiveIt() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1"), cm = login("cm1");
        long id = create(sales, app("PL", "Request Person", "REQPR4321R", "9855000101", 300_000, 24), "PAN", "AADHAAR", "SALARY_SLIP");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        // a personal loan takes no guarantor; credit asks for a co-applicant instead
        assertThat(call(post("/api/applications/" + id + "/party-requests"), co, Map.of("role", "GUARANTOR", "reason", "Thin file needs another person")).body().toString())
                .contains("does not allow a guarantor");
        assertThat(call(post("/api/applications/" + id + "/party-requests"), sales, Map.of("role", "CO_APPLICANT", "reason", "Thin file needs another person")).status()).isEqualTo(403);
        long rid = ok(post("/api/applications/" + id + "/party-requests"), co, Map.of("role", "CO_APPLICANT", "reason", "Income is thin: add the spouse as co-applicant")).body().get("id").asLong();
        assertThat(ok(get("/api/my-work"), sales, null).body().toString()).contains("Credit asked for a co-applicant");
        JsonNode st = states(id, ops);
        if (st.get("SANCTION").asText().startsWith("PENDING_")) {
            String approver = login(Map.of("L1", "co1", "L2", "cm1", "L3", "cro1").get(st.get("SANCTION").asText().replace("PENDING_", "")));
            assertThat(call(post("/api/applications/" + id + "/sanction"), approver, Map.of("note", "Approving within policy")).body().get("code").asText()).isEqualTo("PARTIES_NOT_READY");
        }
        assertThat(call(post("/api/party-requests/" + rid + "/waive"), co, Map.of("note", "Salary credits are regular for two years")).status()).isEqualTo(403);
        assertThat(ok(post("/api/party-requests/" + rid + "/waive"), cm, Map.of("note", "Salary credits are regular for two years")).body().get("status").asText()).isEqualTo("WAIVED");
        assertThat(ok(get("/api/applications/" + id + "/parties"), ops, null).body().get("issues").size()).isZero();
    }

    @Test
    void goldLoanTakesNoPartiesAndScopeIsChecked() throws Exception {
        String sales = login("sales1");
        long id = create(sales, app("GL", "Gold Person", "GLDPG4321G", "9855000201", 100_000, 6));
        assertThat(call(post("/api/applications/" + id + "/parties"), sales, party("CO_APPLICANT", "SPOUSE", "Gold Spouse", "GLDPH4321H", "9855000202", 40, null, false)).body().toString())
                .contains("does not allow a co-applicant");
        assertThat(call(get("/api/parties/999999/invite"), sales, null).status()).isIn(404, 405);
        assertThat(call(post("/api/parties/999999/invite"), sales, null).status()).isEqualTo(404);
        assertThat(call(post("/api/applications/" + id + "/parties"), login("fraud1"), party("CO_APPLICANT", "SPOUSE", "X Y", "GLDPJ4321J", "9855000203", 40, null, false)).status()).isEqualTo(403);
    }
}
