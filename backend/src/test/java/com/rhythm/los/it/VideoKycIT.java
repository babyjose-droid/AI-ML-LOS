package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.DocumentReader;
import com.rhythm.los.document.DocumentReading;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Phase 4: video KYC. The customer joins by link (no login), the official runs the call, frames and the recording
 * are uploaded, the AI result (stubbed) is stored and the official decides. RBI V-CIP guards are checked.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-vkyc"
})
class VideoKycIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @TestConfiguration
    static class StubAi {
        @Bean
        @Primary
        DocumentReader stubReader() {
            return new DocumentReader() {
                public String name() { return "AI-STUB"; }
                public boolean extractsFields() { return false; }
                public DocumentReading read(byte[] b, String ct, String file, String declared) {
                    return new DocumentReading("OK", declared, 0.96, Map.of(), List.of(), Map.of("issues", List.of()), Map.of("score", 0),
                            List.of(), 0.96, name(), false, null);
                }
                public boolean analysesVideoKyc() { return true; }
                public String analyzeVideoKyc(List<Part> frames, List<Part> ids, String pan) {
                    boolean all = frames.stream().map(p -> p.name().split("-")[0]).distinct().count() >= 4;
                    return Json.write(Map.of("status", all ? "PASS" : "REVIEW",
                            "faceMatch", Map.of("matched", true, "score", 0.71, "threshold", 0.363, "against", "PAN"),
                            "liveness", Map.of("passed", all, "checks", List.of()),
                            "reasons", all ? List.of() : List.of("Head turn left and right: turn frames missing"),
                            "framesSeen", frames.size(), "pan", pan));
                }
            };
        }
    }

    @Autowired MockMvc mvc;

    String login(String user) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"Rhythm@123\"}")).andReturn();
        return Json.MAPPER.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

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

    long create(String token, String name, String pan, String mobile) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL");
        m.put("applicantName", name);
        m.put("pan", pan);
        m.put("mobile", mobile);
        m.put("dob", "1990-04-15");
        m.put("city", "Pune");
        m.put("pincode", "411038");
        m.put("businessVintageYears", 5);
        m.put("declaredMonthlyIncome", 80000);
        m.put("essentialExpenses", 25000);
        m.put("loanAmount", 200000);
        m.put("tenureMonths", 24);
        m.put("bankAccountNo", "123456789012");
        m.put("bankIfsc", "ICIC0000123");
        m.put("consentBureau", true);
        m.put("consentAa", true);
        m.put("consentKyc", true);
        return ok(post("/api/applications"), token, m).body().get("id").asLong();
    }

    String token(Res created) { return created.body().get("link").asText().substring("/vkyc/".length()); }

    void frames(long sid, String ops, String... steps) throws Exception {
        var b = multipart("/api/vkyc/" + sid + "/frames");
        int i = 0;
        for (String s : steps) b.file(new MockMultipartFile("frames", s + "-" + (++i) + ".jpg", "image/jpeg", ("jpeg " + s).getBytes()));
        assertThat(call(b, ops, null).status()).isEqualTo(200);
    }

    void recording(long sid, String ops) throws Exception {
        var b = multipart("/api/vkyc/" + sid + "/recording").file(new MockMultipartFile("file", "recording.webm", "video/webm", "webm-bytes".getBytes()));
        assertThat(call(b, ops, null).status()).isEqualTo(200);
    }

    @Test
    void customerJoinsByLinkOfficerRunsTheCallAndDecides() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), ops2 = login("admin");
        long id = create(sales, "Farida Khan", "FARPK7001F", "9811170001");

        assertThat(call(post("/api/applications/" + id + "/vkyc"), sales, null).status()).isEqualTo(403);
        Res created = ok(post("/api/applications/" + id + "/vkyc"), ops, null);
        long sid = created.body().get("session").get("id").asLong();
        String t = token(created);
        assertThat(created.body().get("session").has("tokenHash")).isFalse();

        // customer: no login, link token only
        assertThat(ok(get("/api/vkyc/c/" + t), null, null).body().get("firstName").asText()).isEqualTo("Farida");
        assertThat(call(get("/api/vkyc/c/not-a-real-token"), null, null).status()).isEqualTo(404);
        assertThat(call(post("/api/vkyc/c/" + t + "/join"), null, Map.of("consent", false, "lat", 18.5, "lng", 73.8)).status()).isEqualTo(422);
        assertThat(call(post("/api/vkyc/c/" + t + "/join"), null, Map.of("consent", true)).status()).isEqualTo(422);
        ok(post("/api/vkyc/c/" + t + "/join"), null, Map.of("consent", true, "lat", 18.5204, "lng", 73.8567, "accuracy", 20));

        // official starts; signalling both ways; customers cannot send instructions
        assertThat(call(post("/api/vkyc/" + sid + "/signal"), ops, Map.of("kind", "offer", "payload", "{}")).status()).isEqualTo(403);
        String code = ok(post("/api/vkyc/" + sid + "/start"), ops, null).body().get("challengeCode").asText();
        assertThat(code).matches("\\d{4}");
        assertThat(call(post("/api/vkyc/" + sid + "/start"), ops2, null).status()).isEqualTo(409);
        ok(post("/api/vkyc/" + sid + "/signal"), ops, Map.of("kind", "offer", "payload", "{\"sdp\":\"o\"}"));
        JsonNode got = ok(get("/api/vkyc/c/" + t + "/signal?after=0"), null, null).body();
        assertThat(got.get("signals").get(0).get("kind").asText()).isEqualTo("offer");
        ok(post("/api/vkyc/c/" + t + "/signal"), null, Map.of("kind", "answer", "payload", "{\"sdp\":\"a\"}"));
        assertThat(call(post("/api/vkyc/c/" + t + "/signal"), null, Map.of("kind", "instruction", "payload", "{}")).status()).isEqualTo(403);
        JsonNode officerSees = ok(get("/api/vkyc/" + sid + "/signal?after=0"), ops, null).body();
        assertThat(officerSees.toString()).contains("answer").doesNotContain("\"offer\"");

        // challenges and recording; only the officer on the call can upload
        frames(sid, ops, "front", "front", "left", "right", "code", "pan");
        assertThat(call(multipart("/api/vkyc/" + sid + "/frames").file(new MockMultipartFile("frames", "selfie-1.jpg", "image/jpeg", "x".getBytes())), ops, null).status()).isEqualTo(422);
        recording(sid, ops);
        JsonNode done = ok(post("/api/vkyc/" + sid + "/finish"), ops, null).body();
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("aiStatus").asText()).isEqualTo("PASS");
        JsonNode detail = ok(get("/api/vkyc/" + sid), ops, null).body();
        assertThat(detail.get("frames").size()).isEqualTo(6);
        assertThat(detail.get("analysis").asText()).contains("FARPK7001F");
        assertThat(call(get("/api/vkyc/" + sid + "/frames/0"), ops, null).status()).isEqualTo(200);
        assertThat(call(get("/api/vkyc/" + sid + "/recording"), ops, null).status()).isEqualTo(200);

        // the link cannot be reused, and the call has ended for the customer
        assertThat(call(post("/api/vkyc/c/" + t + "/join"), null, Map.of("consent", true, "lat", 18.5, "lng", 73.8)).status()).isEqualTo(409);
        assertThat(ok(get("/api/vkyc/c/" + t + "/signal?after=0"), null, null).body().toString()).contains("hangup");

        // decision guards
        assertThat(call(post("/api/vkyc/" + sid + "/decision"), ops2, Map.of("approve", true, "codeHeard", true, "note", "Looks fine to me overall")).status()).isEqualTo(403);
        assertThat(call(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", false, "note", "Customer matched")).body().toString()).contains("CODE_NOT_CONFIRMED");
        assertThat(call(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "short")).status()).isEqualTo(422);
        // the personal loan's questions must each be asked and confirmed
        assertThat(detail.get("questions").size()).isEqualTo(3);
        assertThat(call(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Face, PAN and code all verified live",
                "answers", java.util.List.of(true, false, true))).body().toString()).contains("QUESTIONS_NOT_CONFIRMED").contains("Who is your employer?");
        JsonNode approved = ok(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Face, PAN and code all verified live", "answers", java.util.List.of(true, true, true))).body();
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("aiOverride").asBoolean()).isFalse();
        assertThat(ok(get("/api/audit?applicationId=" + id), login("comp1"), null).body().toString()).contains("vkyc.approved").contains("vkyc.customer_joined");
    }

    @Test
    void customerBooksASlotTheQueueShowsItAndAForeignIpBlocksApproval() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, "Meera Sen", "MEEPS7009M", "9811170009");
        Res created = ok(post("/api/applications/" + id + "/vkyc"), ops, null);
        long sid = created.body().get("session").get("id").asLong();
        String t = token(created);

        // the customer picks a time instead of joining now
        JsonNode slots = ok(get("/api/vkyc/c/" + t + "/slots"), null, null).body();
        assertThat(slots.size()).isGreaterThan(10);
        String first = slots.get(0).get("at").asText(), second = slots.get(1).get("at").asText();
        assertThat(call(post("/api/vkyc/c/" + t + "/book"), null, Map.of("slotAt", "2020-01-01T00:00:00Z")).body().get("code").asText()).isEqualTo("BAD_SLOT");
        assertThat(ok(post("/api/vkyc/c/" + t + "/book"), null, Map.of("slotAt", first)).body().get("slotAt").asText()).isNotEmpty();
        JsonNode q = ok(get("/api/vkyc/queue"), ops, null).body();
        assertThat(q.get("booked").toString()).contains("\"sessionId\":" + sid).contains("Meera Sen");
        assertThat(call(get("/api/vkyc/queue"), sales, null).status()).isEqualTo(403);
        // staff move it on the customer's call
        assertThat(ok(post("/api/vkyc/" + sid + "/book"), ops, Map.of("slotAt", second)).body().get("bookedBy").asText()).isEqualTo("ops1");

        // GPS says Pune but the IP address is in the UK (a VPN): the official sees it and cannot approve
        ok(post("/api/vkyc/c/" + t + "/join").header("X-Forwarded-For", "198.51.100.20, 10.0.0.1"), null, Map.of("consent", true, "lat", 18.5204, "lng", 73.8567));
        JsonNode sess = ok(get("/api/vkyc/" + sid), ops, null).body().get("session");
        assertThat(sess.get("inIndia").asBoolean()).isTrue();
        assertThat(sess.get("ipCountry").asText()).isEqualTo("GB");
        assertThat(ok(get("/api/vkyc/queue"), ops, null).body().get("waiting").toString()).contains("\"sessionId\":" + sid).contains("\"ipCountry\":\"GB\"");
        ok(post("/api/vkyc/" + sid + "/start"), ops, null);
        frames(sid, ops, "front", "left", "right", "code", "pan");
        recording(sid, ops);
        ok(post("/api/vkyc/" + sid + "/finish"), ops, null);
        Res blocked = call(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Everything checked properly on the call", "answers", java.util.List.of(true, true, true)));
        assertThat(blocked.body().get("code").asText()).isEqualTo("OUTSIDE_INDIA");
        assertThat(blocked.body().toString()).contains("IP address in GB");
    }

    @Test
    void outsideIndiaCannotBeApprovedAndFailedAiNeedsAReasonedOverride() throws Exception {
        String sales = login("sales1"), ops = login("ops1");

        long id = create(sales, "Ravi Iyer", "RAVPI7002R", "9811170002");
        Res created = ok(post("/api/applications/" + id + "/vkyc"), ops, null);
        long sid = created.body().get("session").get("id").asLong();
        ok(post("/api/vkyc/c/" + token(created) + "/join"), null, Map.of("consent", true, "lat", 51.5072, "lng", -0.1276));
        assertThat(ok(get("/api/vkyc/" + sid), ops, null).body().get("session").get("inIndia").asBoolean()).isFalse();
        ok(post("/api/vkyc/" + sid + "/start"), ops, null);
        frames(sid, ops, "front", "left", "right", "code", "pan");
        recording(sid, ops);
        ok(post("/api/vkyc/" + sid + "/finish"), ops, null);
        assertThat(call(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Everything checked properly on the call")).body().toString()).contains("OUTSIDE_INDIA");
        ok(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", false, "codeHeard", true, "note", "Customer was outside India during the call"));
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("KYC").asText()).isEqualTo("FAILED");
        assertThat(call(post("/api/applications/" + id + "/vkyc"), ops, null).status()).isEqualTo(409);

        // AI checks did not all pass (head turns missing): approval needs a 30+ character reason and is flagged
        long id2 = create(sales, "Meena Pawar", "MEEPP7003M", "9811170003");
        Res c2 = ok(post("/api/applications/" + id2 + "/vkyc"), ops, null);
        long sid2 = c2.body().get("session").get("id").asLong();
        ok(post("/api/vkyc/c/" + token(c2) + "/join"), null, Map.of("consent", true, "lat", 19.076, "lng", 72.8777));
        ok(post("/api/vkyc/" + sid2 + "/start"), ops, null);
        frames(sid2, ops, "front", "pan");
        assertThat(call(post("/api/vkyc/" + sid2 + "/decision"), ops, Map.of("approve", false, "codeHeard", false, "note", "Trying too early")).status()).isEqualTo(409);
        recording(sid2, ops);
        assertThat(ok(post("/api/vkyc/" + sid2 + "/finish"), ops, null).body().get("aiStatus").asText()).isEqualTo("REVIEW");
        assertThat(call(post("/api/vkyc/" + sid2 + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Seems fine on call", "answers", java.util.List.of(true, true, true))).body().toString()).contains("OVERRIDE_REASON");
        JsonNode ok2 = ok(post("/api/vkyc/" + sid2 + "/decision"), ops,
                Map.of("approve", true, "codeHeard", true, "note", "Customer has a neck injury; turned eyes and shoulders, face and PAN clearly matched", "answers", java.util.List.of(true, true, true))).body();
        assertThat(ok2.get("aiOverride").asBoolean()).isTrue();
        assertThat(ok(get("/api/audit?applicationId=" + id2), login("comp1"), null).body().toString()).contains("vkyc.approved_override");
    }

    @Test
    void coApplicantVideoKycClearsTheirOwnKycReviewAndFollowsTheProduct() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), co = login("co1");
        long id = create(sales, "Nikhil Rao", "NIKPR7004N", "9811170004");
        ok(post("/api/applications/" + id + "/submit"), sales, null);
        // PAN 93xx: the mock KYC provider finds the address weak, so her KYC goes to review
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("role", "CO_APPLICANT"); p.put("relationship", "SPOUSE"); p.put("name", "Asha Rao"); p.put("pan", "ASHPR9321A");
        p.put("mobile", "9811170044"); p.put("dob", "1992-02-02"); p.put("city", "Pune");
        long pid = ok(post("/api/applications/" + id + "/parties"), sales, p).body().get("id").asLong();
        // no call before she consents
        assertThat(call(post("/api/applications/" + id + "/vkyc"), ops, Map.of("partyId", pid)).body().toString()).contains("CONSENT_MISSING");
        ok(post("/api/parties/" + pid + "/otp"), sales, null);
        ok(post("/api/parties/" + pid + "/consent"), sales, Map.of("otp", "123456", "consentKyc", true, "consentBureau", true));
        assertThat(ok(post("/api/applications/" + id + "/parties/checks"), ops, null).body().toString()).contains("Asha Rao: KYC review");

        // the personal loan lets operations (not credit) run calls
        assertThat(ok(get("/api/applications/" + id + "/vkyc/policy"), ops, null).body().get("officerRoles").toString()).contains("OPERATIONS");
        assertThat(call(post("/api/applications/" + id + "/vkyc"), co, Map.of("partyId", pid)).body().toString()).contains("NOT_VKYC_OFFICIAL");
        Res created = ok(post("/api/applications/" + id + "/vkyc"), ops, Map.of("partyId", pid));
        long sid = created.body().get("session").get("id").asLong();
        assertThat(created.body().get("session").get("personName").asText()).isEqualTo("Asha Rao");
        assertThat(ok(get("/api/vkyc/c/" + token(created)), null, null).body().get("firstName").asText()).isEqualTo("Asha");
        ok(post("/api/vkyc/c/" + token(created) + "/join"), null, Map.of("consent", true, "lat", 18.52, "lng", 73.85));
        ok(post("/api/vkyc/" + sid + "/start"), ops, null);
        frames(sid, ops, "front", "left", "right", "code", "pan");
        recording(sid, ops);
        JsonNode done = ok(post("/api/vkyc/" + sid + "/finish"), ops, null).body();
        assertThat(done.get("aiStatus").asText()).isEqualTo("PASS");
        // matched against her own PAN, never the applicant's
        assertThat(ok(get("/api/vkyc/" + sid), ops, null).body().get("analysis").asText()).contains("ASHPR9321A");
        ok(post("/api/vkyc/" + sid + "/decision"), ops, Map.of("approve", true, "codeHeard", true, "note", "Face and PAN matched her own documents",
                "answers", java.util.List.of(true, true, true)));
        JsonNode parties = ok(get("/api/applications/" + id + "/parties"), ops, null).body();
        assertThat(parties.get("parties").get(0).get("party").get("kycState").asText()).isEqualTo("VERIFIED");
        assertThat(parties.get("parties").get(0).get("vkyc").asText()).isEqualTo("APPROVED");
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("KYC").asText()).isNotEqualTo("FAILED");
        // one approved call per person
        assertThat(call(post("/api/applications/" + id + "/vkyc"), ops, Map.of("partyId", pid)).body().toString()).contains("ALREADY_APPROVED");
    }
}
