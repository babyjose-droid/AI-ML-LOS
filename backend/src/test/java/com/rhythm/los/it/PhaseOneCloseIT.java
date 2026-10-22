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

/** Phase 1 close: send back, approve with conditions, review queue, consent ledger, credit memo, data capture, Decision API, policy lifecycle. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=300",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-phase1",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class PhaseOneCloseIT {

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

    String login(String user) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", user, "password", "Rhythm@123")).body().get("token").asText();
    }

    Map<String, Object> app(String name, String pan, String mobile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "1 Test Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000); m.put("essentialExpenses", 30000);
        m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test"); m.put("bankAccountNo", "5010012345678");
        m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        m.put("preferredLanguage", "mr");
        return m;
    }

    long create(String token, Map<String, Object> body, String... docs) throws Exception {
        long id = ok(post("/api/applications"), token, body).body().get("id").asLong();
        for (String t : docs) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + token)).andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        }
        return id;
    }

    JsonNode states(long id, String token) throws Exception { return ok(get("/api/applications/" + id), token, null).body().get("states"); }

    @Test
    void sendBackReopensTheDecisionAndApprovalCanAddConditions() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1"), cm = login("cm1");
        long id = create(sales, app("Send Back", "SENPB1002S", "9822000001"), "PAN", "AADHAAR", "SALARY_SLIP");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        assertThat(states(id, ops).get("SANCTION").asText()).isEqualTo("PENDING_L2");

        // review queue shows the case to the L2 approver
        JsonNode q = ok(get("/api/review-queue"), cm, null).body();
        JsonNode card = null;
        for (JsonNode c : q) if (c.get("applicationId").asLong() == id) card = c;
        assertThat(card).isNotNull();
        assertThat(card.get("level").asText()).isEqualTo("L2");
        assertThat(card.get("canAct").asBoolean()).isTrue();
        assertThat(card.get("sentBackBefore").asBoolean()).isFalse();
        assertThat(call(get("/api/review-queue"), sales, null).status()).isEqualTo(403);

        assertThat(call(post("/api/applications/" + id + "/sanction/send-back"), cm, Map.of("note", "short")).status()).isEqualTo(422);
        assertThat(call(post("/api/applications/" + id + "/sanction/send-back"), co, Map.of("note", "Check the salary credits again")).status()).isEqualTo(403);
        ok(post("/api/applications/" + id + "/sanction/send-back"), cm, Map.of("note", "Check the salary credits against the slip again"));
        JsonNode st = states(id, cm);
        assertThat(st.get("SANCTION").asText()).isEqualTo("NOT_STARTED");
        assertThat(st.get("DECISION").asText()).isEqualTo("PENDING");

        // automation does not simply re-decide: credit has to re-assess
        assertThat(ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps").toString()).contains("Sent back from sanction");
        assertThat(states(id, ops).get("DECISION").asText()).isEqualTo("PENDING");
        assertThat(ok(get("/api/my-work"), co, null).body().toString()).contains("Sent back from sanction");

        ok(post("/api/applications/" + id + "/decision/run"), co, null);
        assertThat(states(id, co).get("SANCTION").asText()).isEqualTo("PENDING_L2");
        assertThat(ok(get("/api/my-work"), co, null).body().toString()).doesNotContain("Sent back from sanction");
        JsonNode again = ok(get("/api/review-queue"), cm, null).body();
        boolean flagged = false;
        for (JsonNode c : again) if (c.get("applicationId").asLong() == id) flagged = c.get("sentBackBefore").asBoolean();
        assertThat(flagged).isTrue();

        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of("conditions", List.of("Post-dated cheques for 3 EMIs", " ")));
        JsonNode recs = ok(get("/api/applications/" + id + "/sanctions"), cm, null).body();
        List<String> actions = new ArrayList<>();
        String conditions = null;
        for (JsonNode r : recs) {
            actions.add(r.get("action").asText());
            if ("APPROVED".equals(r.get("action").asText())) conditions = r.get("conditionsJson").asText();
        }
        assertThat(actions).contains("SENT_BACK", "APPROVED");
        assertThat(conditions).contains("Post-dated cheques for 3 EMIs").doesNotContain("\" \"");
    }

    @Test
    void consentLedgerRecordsEachSourceAndWithdrawalStopsPulls() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, app("Consent Person", "CONPP1002C", "9822000002"));
        ok(post("/api/applications/" + id + "/submit"), sales, null);
        JsonNode list = ok(get("/api/applications/" + id + "/consents"), ops, null).body();
        Set<String> sources = new HashSet<>();
        for (JsonNode c : list) {
            sources.add(c.get("source").asText());
            assertThat(c.get("language").asText()).isEqualTo("mr");
            assertThat(c.get("purpose").asText()).isNotBlank();
        }
        assertThat(sources).containsExactlyInAnyOrder("KYC", "AA", "BUREAU", "COMMUNICATION");

        assertThat(call(post("/api/applications/" + id + "/consents/KYC/withdraw"), sales, Map.of("note", "no")).status()).isEqualTo(422);
        assertThat(call(post("/api/applications/" + id + "/consents/KYC/withdraw"), login("co1"), Map.of("note", "Borrower called the branch")).status()).isEqualTo(403);
        JsonNode w = ok(post("/api/applications/" + id + "/consents/KYC/withdraw"), sales, Map.of("note", "Borrower called the branch and asked")).body();
        assertThat(w.get("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(w.get("effect").asText()).contains("No further identity checks");
        assertThat(call(post("/api/applications/" + id + "/consents/KYC/withdraw"), sales, Map.of("note", "Borrower called the branch and asked")).status()).isEqualTo(409);

        Res kyc = call(post("/api/applications/" + id + "/kyc/run"), ops, null);
        assertThat(kyc.status()).isEqualTo(409);
        assertThat(kyc.body().get("code").asText()).isEqualTo("CONSENT_WITHDRAWN");
        assertThat(states(id, ops).get("KYC").asText()).isEqualTo("NOT_STARTED");
    }

    @Test
    void creditMemoIsEditedVersionedSignedAndLocked() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1");
        long id = create(sales, app("Memo Person", "MEMPP1002M", "9822000003"), "PAN", "AADHAAR", "SALARY_SLIP");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode v = ok(get("/api/applications/" + id + "/memo"), co, null).body();
        assertThat(v.get("memo").get("version").asInt()).isEqualTo(1);
        assertThat(v.get("memo").get("status").asText()).isEqualTo("DRAFT");
        assertThat(v.get("canEdit").asBoolean()).isTrue();
        assertThat(ok(get("/api/applications/" + id + "/memo"), ops, null).body().get("canEdit").asBoolean()).isFalse();
        String body = v.get("memo").get("body").asText();

        assertThat(call(put("/api/applications/" + id + "/memo"), co, Map.of("body", "too short")).status()).isEqualTo(422);
        assertThat(call(put("/api/applications/" + id + "/memo"), ops, Map.of("body", body)).status()).isEqualTo(403);
        JsonNode e = ok(put("/api/applications/" + id + "/memo"), co, Map.of("body", body + "\nOfficer note: shop turnover about 987654 a year.")).body();
        assertThat(e.get("memo").get("version").asInt()).isEqualTo(2);
        assertThat(e.get("unsourcedNumbers").toString()).contains("987654");
        assertThat(e.get("history").size()).isEqualTo(2);

        JsonNode s = ok(post("/api/applications/" + id + "/memo/sign"), co, null).body();
        assertThat(s.get("memo").get("status").asText()).isEqualTo("SIGNED");
        assertThat(s.get("memo").get("signedBy").asText()).isEqualTo("co1");
        assertThat(s.get("canEdit").asBoolean()).isFalse();
        assertThat(call(put("/api/applications/" + id + "/memo"), co, Map.of("body", body)).body().get("code").asText()).isEqualTo("LOCKED");

        // data-capture checklist: lead data captured, sanction data still missing
        JsonNode items = ok(get("/api/applications/" + id + "/data-capture"), ops, null).body();
        Map<String, Boolean> captured = new HashMap<>();
        for (JsonNode i : items) captured.put(i.get("stage").asText() + "/" + i.get("item").asText(), i.get("captured").asBoolean());
        assertThat(captured.get("lead/PAN")).isTrue();
        assertThat(captured.get("lead/Preferred language")).isTrue();
        assertThat(captured.get("docs/PAN card image")).isTrue();
        assertThat(captured.get("cam/Memo text, edits, signer, time")).isTrue();
        assertThat(captured.get("sanction/KFS acceptance OTP and language")).isFalse();
    }

    @Test
    void statelessDecisionApiRunsTheEngineWithoutStoringAnything() throws Exception {
        String co = login("co1"), ops = login("ops1");
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("id", "EXT-1"); in.put("name", "External Applicant"); in.put("segment", "SALARIED"); in.put("age", 35); in.put("vintage", 5);
        in.put("kyc", Map.of("name", 0.95, "dob", 1.0, "id", 1.0, "address", 0.9, "contact", 1.0));
        in.put("declaredIncome", 70000); in.put("essential", 25000);
        List<Double> inflows = Collections.nCopies(12, 72000.0), outflows = Collections.nCopies(12, 50000.0);
        in.put("bank", Map.of("inflows", inflows, "outflows", outflows, "avgBalance", 40000, "bounces", 0, "detectedEmi", 0, "gambling", 0,
                "source", "AA", "employerMismatch", false));
        in.put("bureau", Map.of("score", 760, "active", 1, "maxDpd12", 0, "ever90", false, "enq6", 1, "ageM", 60, "emi", 5000));
        in.put("jlg", false); in.put("secured", false);
        in.put("fraud", Map.of("dupLinks", 0, "docTamper", false, "velocity", 0));
        in.put("loan", Map.of("amount", 200000, "tenure", 24));
        Map<String, Object> req = Map.of("productCode", "PL", "input", in);

        JsonNode a = ok(post("/api/v1/credit/decision"), co, req).body();
        assertThat(a.get("decision").asText()).isNotBlank();
        assertThat(a.get("riskBand").asText()).isNotBlank();
        assertThat(a.get("policyVersion").asText()).startsWith("POL-");
        assertThat(a.has("rulesJson")).isTrue();
        assertThat(a.has("id")).isFalse();
        JsonNode b = ok(post("/api/v1/credit/decision"), co, req).body();
        assertThat(b.get("inputHash").asText()).isEqualTo(a.get("inputHash").asText());
        assertThat(b.get("pd").asDouble()).isEqualTo(a.get("pd").asDouble());

        assertThat(call(post("/api/v1/credit/decision"), ops, req).status()).isEqualTo(403);
        assertThat(call(post("/api/v1/credit/decision"), co, Map.of("input", Map.of("id", "X"))).status()).isEqualTo(422);
    }

    @Test
    void policyOverridesEffectiveDateCancelAndRollback() throws Exception {
        String cm = login("cm1"), cro = login("cro1");
        String liveBefore = ok(get("/api/policies/live"), cm, null).body().get("version").asText();

        // a draft with an MSME override, taking effect tomorrow
        long d1 = ok(post("/api/policies/drafts"), cm, Map.of("notes", "Tighter FOIR for MSME")).body().get("id").asLong();
        String tomorrow = LocalDate.now().plusDays(2).toString();
        assertThat(call(put("/api/policies/" + d1), cm, Map.of("overrides", Map.of("region:WEST", Map.of("maxFoir", 0.45)))).body().get("code").asText()).isEqualTo("BAD_SCOPE");
        assertThat(call(put("/api/policies/" + d1), cm, Map.of("overrides", Map.of("segment:MSME", Map.of("maxFoir", 5)))).body().get("code").asText()).isEqualTo("INVALID_OVERRIDE");
        ok(put("/api/policies/" + d1), cm, Map.of("overrides", Map.of("segment:MSME", Map.of("maxFoir", 0.45)), "effectiveFrom", tomorrow, "setEffectiveFrom", true));
        JsonNode det = ok(get("/api/policies/" + d1), cm, null).body();
        assertThat(det.get("overrides").get("segment:MSME").get("maxFoir").asDouble()).isEqualTo(0.45);
        JsonNode sim = ok(post("/api/policies/" + d1 + "/simulate"), cm, null).body();
        JsonNode book = sim.get("liveBook");
        assertThat(book.get("loans").asInt()).isGreaterThan(0);
        assertThat(book.get("classification").has("NPA")).isTrue();
        assertThat(book.get("live").has("topUpEligible")).isTrue();
        assertThat(sim.get("overrides").get("draft").has("segment:MSME")).isTrue();
        ok(post("/api/policies/" + d1 + "/submit"), cm, null);
        JsonNode appr = ok(post("/api/policies/" + d1 + "/decision"), cro, Map.of("approve", true, "note", "Approved for next week as discussed")).body();
        assertThat(appr.get("status").asText()).isEqualTo("APPROVED");
        assertThat(ok(get("/api/policies/live"), cm, null).body().get("version").asText()).isEqualTo(liveBefore);
        // a scheduled version blocks a new draft until it is live or cancelled
        assertThat(call(post("/api/policies/drafts"), cm, Map.of()).body().get("code").asText()).isEqualTo("DRAFT_EXISTS");
        assertThat(call(post("/api/policies/" + d1 + "/cancel"), cm, Map.of("note", "Not needed any more")).status()).isEqualTo(403);
        assertThat(ok(post("/api/policies/" + d1 + "/cancel"), cro, Map.of("note", "Not needed any more after the review")).body().get("status").asText()).isEqualTo("REJECTED");

        // a change that goes live at once, then is rolled back
        long d2 = ok(post("/api/policies/drafts"), cm, Map.of("notes", "Lower auto-approve PD")).body().get("id").asLong();
        ok(put("/api/policies/" + d2), cm, Map.of("params", Map.of("approvePd", 0.05)));
        ok(post("/api/policies/" + d2 + "/simulate"), cm, null);
        ok(post("/api/policies/" + d2 + "/submit"), cm, null);
        ok(post("/api/policies/" + d2 + "/decision"), cro, Map.of("approve", true, "note", "Approved after the simulation review"));
        String newLive = ok(get("/api/policies/live"), cm, null).body().get("version").asText();
        assertThat(newLive).isNotEqualTo(liveBefore);
        long retiredId = 0;
        for (JsonNode v : ok(get("/api/policies"), cm, null).body())
            if (liveBefore.equals(v.get("version").asText())) { retiredId = v.get("id").asLong(); assertThat(v.get("status").asText()).isEqualTo("RETIRED"); }
        assertThat(call(post("/api/policies/" + retiredId + "/rollback"), cm, Map.of("note", "Approval rate fell too far")).status()).isEqualTo(403);
        assertThat(call(post("/api/policies/" + retiredId + "/rollback"), cro, Map.of("note", "short")).status()).isEqualTo(422);
        JsonNode rb = ok(post("/api/policies/" + retiredId + "/rollback"), cro, Map.of("note", "Approval rate fell too far in the first week")).body();
        assertThat(rb.get("status").asText()).isEqualTo("LIVE");
        assertThat(rb.get("rollbackOf").asText()).isEqualTo(liveBefore);
        JsonNode live = ok(get("/api/policies/live"), cm, null).body();
        assertThat(live.get("version").asText()).isEqualTo(rb.get("version").asText());
        assertThat(live.get("approvePd").asDouble()).isNotEqualTo(0.05);
        assertThat(ok(get("/api/audit"), cro, null).body().toString()).contains("policy.rolled_back");
    }
}
