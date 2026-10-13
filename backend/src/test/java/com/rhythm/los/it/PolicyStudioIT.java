package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 5: credit policy studio with simulation and maker-checker. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=400",
        "rhythm.storage.path=target/test-documents-policy"
})
class PolicyStudioIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;

    String login(String user) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + user + "\",\"password\":\"Rhythm@123\"}")).andReturn();
        return Json.MAPPER.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    record Res(int status, JsonNode body) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
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

    @Test
    void draftSimulateApproveByAnotherPersonGoesLive() throws Exception {
        String cm = login("cm1"), cro = login("cro1"), ops = login("ops1");
        String before = ok(get("/api/policies/live"), ops, null).body().get("version").asText();

        assertThat(call(post("/api/policies/drafts"), ops, Map.of()).status()).isEqualTo(403);
        JsonNode draft = ok(post("/api/policies/drafts"), cm, Map.of("notes", "Tighten auto-approval")).body();
        long id = draft.get("id").asLong();
        assertThat(draft.get("version").asText()).endsWith("-v5");
        assertThat(call(post("/api/policies/drafts"), cro, Map.of()).body().toString()).contains("DRAFT_EXISTS");

        // validation and ownership
        assertThat(call(put("/api/policies/" + id), cm, Map.of("params", Map.of("approvePd", 0.2))).body().toString()).contains("Auto-approve PD must be below");
        assertThat(call(put("/api/policies/" + id), cm, Map.of("params", Map.of("bogus", 1))).status()).isEqualTo(422);
        assertThat(call(put("/api/policies/" + id), cro, Map.of("params", Map.of("approvePd", 0.05))).status()).isEqualTo(403);
        ok(put("/api/policies/" + id), cm, Map.of("params", Map.of("approvePd", 0.05, "maxFoir", 0.45), "notes", "Losses above plan in Q2"));
        assertThat(call(post("/api/policies/" + id + "/submit"), cm, null).body().toString()).contains("SIMULATE_FIRST");

        JsonNode sim = ok(post("/api/policies/" + id + "/simulate"), cm, null).body();
        assertThat(sim.get("book").get("loans").asInt()).isEqualTo(400);
        assertThat(sim.get("book").get("source").asText()).isEqualTo("SYNTHETIC");
        assertThat(sim.get("changes").size()).isEqualTo(2);
        double liveRate = sim.get("book").get("live").get("approvalRate").asDouble(), draftRate = sim.get("book").get("draft").get("approvalRate").asDouble();
        assertThat(draftRate).isLessThanOrEqualTo(liveRate);
        assertThat(sim.get("book").get("live").has("badRateApproved")).isTrue();

        ok(post("/api/policies/" + id + "/submit"), cm, null);
        assertThat(call(put("/api/policies/" + id), cm, Map.of("params", Map.of("approvePd", 0.06))).status()).isEqualTo(409);
        assertThat(ok(get("/api/my-work"), cro, null).body().toString()).contains("waiting for your approval");

        // checker rules
        assertThat(call(post("/api/policies/" + id + "/decision"), cm, Map.of("approve", true, "note", "Approving my own policy")).status()).isEqualTo(403);
        assertThat(call(post("/api/policies/" + id + "/decision"), cro, Map.of("approve", true, "note", "ok")).status()).isEqualTo(422);
        JsonNode liveNow = ok(post("/api/policies/" + id + "/decision"), cro, Map.of("approve", true, "note", "Agreed with risk committee on 13 Oct")).body();
        assertThat(liveNow.get("status").asText()).isEqualTo("LIVE");

        JsonNode live = ok(get("/api/policies/live"), ops, null).body();
        assertThat(live.get("version").asText()).isEqualTo(draft.get("version").asText()).isNotEqualTo(before);
        assertThat(live.get("approvePd").asDouble()).isEqualTo(0.05);
        String all = ok(get("/api/policies"), ops, null).body().toString();
        assertThat(all).contains("RETIRED");
        assertThat(ok(get("/api/audit"), login("comp1"), null).body().toString()).contains("policy.approved_live").contains("Auto-approve up to PD 0.07 → 0.05");

        // the CRO's own draft can be sent back by nobody else but cannot be approved by the CRO
        JsonNode d2 = ok(post("/api/policies/drafts"), cro, Map.of()).body();
        long id2 = d2.get("id").asLong();
        ok(put("/api/policies/" + id2), cro, Map.of("params", Map.of("maxEnq6", 8)));
        ok(post("/api/policies/" + id2 + "/simulate"), cro, null);
        ok(post("/api/policies/" + id2 + "/submit"), cro, null);
        assertThat(call(post("/api/policies/" + id2 + "/decision"), cro, Map.of("approve", true, "note", "Approving my own change")).body().toString()).contains("MAKER_CHECKER");
    }
}
