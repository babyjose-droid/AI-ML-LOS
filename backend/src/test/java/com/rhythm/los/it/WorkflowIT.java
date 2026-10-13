package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.flow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

/** Workflow designer: product stage sequences, maker-checker publishing, stage gating, skip rules and SLA breaches. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-flow",
        "rhythm.workflow.sla-initial-delay-ms=86400000"
})
class WorkflowIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired WorkflowService workflow;

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

    Map<String, Object> application(String product, String name, String pan, String mobile, int amount, int tenure) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", product); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", "1988-06-12"); m.put("city", "Nashik"); m.put("pincode", "422001"); m.put("address", "12 Main Road");
        m.put("businessName", "Village dairy"); m.put("businessVintageYears", 5); m.put("declaredMonthlyIncome", 90000);
        m.put("essentialExpenses", 20000); m.put("loanAmount", amount); m.put("tenureMonths", tenure); m.put("purpose", "Working capital");
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

    JsonNode stage(long id, String token, String key) throws Exception {
        for (JsonNode s : ok(get("/api/applications/" + id + "/stages"), token, null).body().get("stages"))
            if (key.equals(s.get("key").asText())) return s;
        return null;
    }

    long jlg(String name, String pan, String mobile) throws Exception {
        String sales = staff("sales1"), ops = staff("ops1");
        long id = ok(post("/api/applications"), sales, application("JLG", name, pan, mobile, 40000, 12)).body().get("id").asLong();
        upload(id, sales, "AADHAAR");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        return id;
    }

    @Test
    void jlgGroupStagesGateTheDecisionAndAFailedGrtRejects() throws Exception {
        String field = staff("field1"), co = staff("co1"), sales = staff("sales1");
        long id = jlg("Sunita Bai", "SUNPB1111S", "9811190001");

        JsonNode app = ok(get("/api/applications/" + id), co, null).body();
        assertThat(app.get("application").get("workflowVersion").asInt()).isPositive();
        assertThat(app.get("blockers").toString()).contains("Group formation (CGT)");
        assertThat(app.get("states").get("DECISION").asText()).isEqualTo("PENDING");
        assertThat(app.get("states").get("FIELD").asText()).isEqualTo("NOT_REQUIRED"); // no field visit stage in the JLG workflow

        JsonNode cgt = stage(id, field, "cgt");
        assertThat(cgt.get("status").asText()).isIn("ACTIVE", "PENDING");
        assertThat(cgt.get("canComplete").asBoolean()).isTrue();
        assertThat(stage(id, sales, "cgt").get("canComplete").asBoolean()).isFalse();
        assertThat(call(post("/api/applications/" + id + "/stages/cgt/complete"), sales,
                Map.of("outcome", "PASS", "note", "Group formed by sales")).status()).isEqualTo(403);
        // GRT must wait for CGT
        assertThat(call(post("/api/applications/" + id + "/stages/grt/complete"), co,
                Map.of("outcome", "PASS", "note", "Group passed the test")).status()).isEqualTo(403);
        assertThat(call(post("/api/applications/" + id + "/stages/cgt/complete"), field,
                Map.of("outcome", "PASS", "note", "short")).status()).isEqualTo(422);

        ok(post("/api/applications/" + id + "/stages/cgt/complete"), field, Map.of("outcome", "PASS",
                "note", "Five member group formed, three training sessions held",
                "data", Map.of("groupName", "Lakshmi SHG", "members", 5, "sessionsHeld", 3, "ignored", "x")));
        JsonNode done = stage(id, co, "cgt");
        assertThat(done.get("status").asText()).isEqualTo("DONE");
        assertThat(done.get("data").get("groupName").asText()).isEqualTo("Lakshmi SHG");
        assertThat(done.get("data").has("ignored")).isFalse();
        assertThat(stage(id, co, "grt").get("canComplete").asBoolean()).isTrue();

        ok(post("/api/applications/" + id + "/stages/grt/complete"), co, Map.of("outcome", "FAIL",
                "note", "Members could not explain repayment schedule", "data", Map.of("membersPresent", 3)));
        app = ok(get("/api/applications/" + id), co, null).body();
        assertThat(app.get("states").get("APP").asText()).isEqualTo("REJECTED");
        assertThat(app.get("application").get("rejectionReason").asText()).contains("Group recognition test");
    }

    @Test
    void jlgPassingGroupStagesLetsTheEngineDecide() throws Exception {
        String field = staff("field1"), co = staff("co1"), ops = staff("ops1");
        long id = jlg("Radha Kumari", "RADPK2222R", "9811190002");
        ok(post("/api/applications/" + id + "/stages/cgt/complete"), field, Map.of("outcome", "PASS", "note", "Group of five formed and trained"));
        ok(post("/api/applications/" + id + "/stages/grt/complete"), co, Map.of("outcome", "PASS", "note", "All members passed the recognition test"));
        JsonNode app = ok(get("/api/applications/" + id), co, null).body();
        assertThat(app.get("blockers").toString()).doesNotContain("Workflow stage");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        app = ok(get("/api/applications/" + id), co, null).body();
        if (app.get("blockers").isEmpty() || "FETCHED".equals(app.get("states").get("DATA").asText()))
            assertThat(app.get("states").get("DECISION").asText()).isNotEqualTo("PENDING");
    }

    @Test
    void designerMakerCheckerSkipRuleAndInFlightVersion() throws Exception {
        String admin = staff("admin"), cro = staff("cro1"), cm = staff("cm1"), sales = staff("sales1"), ops = staff("ops1");
        JsonNode lib = ok(get("/api/workflows/library"), sales, null).body();
        assertThat(lib.get("stages").toString()).contains("\"key\":\"gold\"");
        JsonNode overview = ok(get("/api/workflows"), sales, null).body();
        assertThat(overview.toString()).contains("BL-UNS").contains("CV-SEC").contains("GL");

        // an application started on v1 (field visit above ₹5 L)
        long before = ok(post("/api/applications"), sales, application("BL-UNS", "Prakash Rao", "PRAPR3333P", "9811190003", 600000, 24)).body().get("id").asLong();

        assertThat(call(post("/api/workflows/drafts"), sales, Map.of("productCode", "BL-UNS")).status()).isEqualTo(403);
        JsonNode draft = ok(post("/api/workflows/drafts"), cm, Map.of("productCode", "BL-UNS")).body();
        long vid = draft.get("id").asLong();
        assertThat(draft.get("version").asInt()).isEqualTo(2);
        assertThat(draft.get("issues").isEmpty()).isTrue();
        assertThat(call(post("/api/workflows/drafts"), admin, Map.of("productCode", "BL-UNS")).body().toString()).contains("DRAFT_EXISTS");

        List<Map<String, Object>> stages = Json.MAPPER.convertValue(draft.get("stages"), List.class);
        // break it: remove KYC, put underwriting first after lead, bad skip rule
        List<Map<String, Object>> broken = new ArrayList<>(stages);
        broken.removeIf(s -> "kyc".equals(s.get("key")));
        broken.forEach(s -> { if ("field".equals(s.get("key"))) s.put("skip", "amount <= banana"); });
        JsonNode bad = ok(put("/api/workflows/" + vid), cm, Map.of("stages", broken, "notes", "Skip field visit for small loans")).body();
        assertThat(bad.get("issues").toString()).contains("Consent & KYC is mandatory").contains("skip rule");
        assertThat(call(post("/api/workflows/" + vid + "/submit"), cm, null).body().toString()).contains("WORKFLOW_INVALID");

        stages.forEach(s -> {
            if ("field".equals(s.get("key"))) { s.put("skip", "amount <= 8L"); s.put("sla", 48); }
            if ("uw".equals(s.get("key"))) s.put("skip", "amount < 1L");
        });
        bad = ok(put("/api/workflows/" + vid), cm, Map.of("stages", stages, "notes", "Skip field visit up to 8 lakh")).body();
        assertThat(bad.get("issues").toString()).contains("cannot have a skip rule");
        stages.forEach(s -> { if ("uw".equals(s.get("key"))) s.put("skip", ""); });
        JsonNode good = ok(put("/api/workflows/" + vid), cm, Map.of("stages", stages, "notes", "Skip field visit up to 8 lakh")).body();
        assertThat(good.get("issues").isEmpty()).isTrue();
        assertThat(good.get("changes").toString()).contains("skip rule").contains("SLA 24 h → 48 h");

        ok(post("/api/workflows/" + vid + "/submit"), cm, null);
        assertThat(call(put("/api/workflows/" + vid), cm, Map.of("stages", stages)).status()).isEqualTo(409);
        assertThat(call(post("/api/workflows/" + vid + "/decision"), admin, Map.of("approve", true, "note", "Looks fine to me")).status()).isEqualTo(403);
        assertThat(ok(get("/api/my-work"), cro, null).body().toString()).contains("Workflow BL-UNS v2 waiting for your approval");
        ok(post("/api/workflows/" + vid + "/decision"), cro, Map.of("approve", true, "note", "Field visits below 8 lakh add little; approved"));
        overview = ok(get("/api/workflows"), sales, null).body();
        for (JsonNode p : overview) if ("BL-UNS".equals(p.get("productCode").asText())) assertThat(p.get("published").get("version").asInt()).isEqualTo(2);

        // new application follows v2: field visit skipped
        long after = ok(post("/api/applications"), sales, application("BL-UNS", "Farhan Ali", "FARPA4444F", "9811190004", 600000, 24)).body().get("id").asLong();
        upload(after, sales, "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + after + "/submit"), sales, null);
        JsonNode a2 = ok(get("/api/applications/" + after), ops, null).body();
        assertThat(a2.get("application").get("workflowVersion").asInt()).isEqualTo(2);
        assertThat(a2.get("states").get("FIELD").asText()).isEqualTo("NOT_REQUIRED");
        JsonNode f2 = stage(after, ops, "field");
        assertThat(f2.get("status").asText()).isEqualTo("SKIPPED");
        assertThat(f2.get("skipReason").asText()).isEqualTo("amount <= 8L");

        // the application already in flight keeps v1 and its field visit
        upload(before, sales, "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + before + "/submit"), sales, null);
        JsonNode a1 = ok(get("/api/applications/" + before), ops, null).body();
        assertThat(a1.get("application").get("workflowVersion").asInt()).isEqualTo(1);
        assertThat(a1.get("states").get("FIELD").asText()).isEqualTo("REQUIRED");
    }

    @Test
    void breachedStageIsEscalated() throws Exception {
        String admin = staff("admin"), cro = staff("cro1"), co = staff("co1"), field = staff("field1");
        JsonNode draft = ok(post("/api/workflows/drafts"), admin, Map.of("productCode", "JLG")).body();
        long vid = draft.get("id").asLong();
        List<Map<String, Object>> stages = Json.MAPPER.convertValue(draft.get("stages"), List.class);
        stages.forEach(s -> { if ("cgt".equals(s.get("key"))) s.put("onFailure", "ESCALATE"); });
        ok(put("/api/workflows/" + vid), admin, Map.of("stages", stages, "notes", "Escalate slow group formation"));
        ok(post("/api/workflows/" + vid + "/submit"), admin, null);
        ok(post("/api/workflows/" + vid + "/decision"), cro, Map.of("approve", true, "note", "Escalation helps branch follow-up"));

        long id = jlg("Kamala Devi", "KAMPD5555K", "9811190005");
        assertThat(ok(get("/api/my-work"), field, null).body().toString()).contains("Group formation (CGT)");
        assertThat(ok(get("/api/my-work"), co, null).body().toString()).doesNotContain("Kamala Devi");
        assertThat(stage(id, co, "cgt").get("canComplete").asBoolean()).isFalse();

        jdbc.update("update app_stage set status = 'ACTIVE', due_at = now() - interval '1 hour' where application_id = ? and stage_key = 'cgt'", id);
        assertThat(workflow.slaSweep()).isGreaterThanOrEqualTo(1);
        JsonNode cgt = stage(id, co, "cgt");
        assertThat(cgt.get("breachedAt").isNull()).isFalse();
        assertThat(cgt.get("escalatedTo").asText()).isEqualTo("CREDIT_OFFICER");
        assertThat(cgt.get("canComplete").asBoolean()).isTrue();
        assertThat(ok(get("/api/my-work"), co, null).body().toString()).contains("SLA breached");
        assertThat(workflow.slaSweep()).isZero(); // a breach is acted on once
        ok(post("/api/applications/" + id + "/stages/cgt/complete"), co, Map.of("outcome", "PASS", "note", "Officer completed group training on visit"));
        assertThat(stage(id, co, "cgt").get("status").asText()).isEqualTo("DONE");
    }
}
