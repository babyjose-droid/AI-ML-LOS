package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.security.Totp;
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

import java.time.Instant;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 2 field officer app: assigned visits, checklist and photo validation, location and photo-reuse checks, idempotent sync. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-field",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000",
        "rhythm.notify.initial-delay-ms=86400000",
        "rhythm.push.enabled=false"
})
class FieldAppIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    record Res(int status, JsonNode body, String raw) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        MvcResult r = mvc.perform(b).andReturn();
        String s = r.getResponse().getContentAsString();
        JsonNode n = null;
        try { n = s.isEmpty() ? null : Json.MAPPER.readTree(s); } catch (Exception ignored) { }
        return new Res(r.getResponse().getStatus(), n, s);
    }

    Res ok(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        Res r = call(b, token, body);
        assertThat(r.status()).as(r.raw()).isEqualTo(200);
        return r;
    }

    String login(String user, String password) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", user, "password", password)).body().get("token").asText();
    }

    String login(String user) throws Exception { return login(user, "Rhythm@123"); }

    long userId(String admin, String username) throws Exception {
        for (JsonNode u : ok(get("/api/admin/users"), admin, null).body()) if (username.equals(u.get("username").asText())) return u.get("id").asLong();
        throw new AssertionError(username);
    }

    long branchId(String token, String code) throws Exception {
        for (JsonNode b : ok(get("/api/branches"), token, null).body()) if (code.equals(b.get("code").asText())) return b.get("id").asLong();
        throw new AssertionError(code);
    }

    Map<String, Object> app(String name, String pan, String mobile, String pin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(34).minusMonths(1).toString()); m.put("city", "Mumbai"); m.put("address", "1 Test Road");
        m.put("pincode", pin); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000); m.put("essentialExpenses", 30000);
        m.put("loanAmount", 300000); m.put("tenureMonths", 24); m.put("purpose", "Test"); m.put("bankAccountNo", "5010012345678");
        m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
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

    static final double LAT = 19.9975, LNG = 73.7898;

    Map<String, Object> kirana(String name, String pan, String mobile) {
        Map<String, Object> m = app(name, pan, mobile, "422001");
        m.put("productCode", "BL-UNS");
        m.put("loanAmount", 600000);
        m.put("tenureMonths", 36);
        m.put("declaredMonthlyIncome", 95000);
        m.put("industry", "KIRANA");
        m.put("addressLat", LAT);
        m.put("addressLng", LNG);
        m.put("businessName", "Shree General Stores");
        return m;
    }

    static byte[] jpeg(long seed) throws Exception {
        BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seed);
        for (int y = 0; y < 120; y += 10)
            for (int x = 0; x < 160; x += 10) {
                int c = r.nextInt(0xFFFFFF);
                for (int yy = y; yy < y + 10; yy++) for (int xx = x; xx < x + 10; xx++) img.setRGB(xx, yy, c);
            }
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", o);
        return o.toByteArray();
    }

    Res sync(long appId, String token, Map<String, Object> visit, Map<String, byte[]> photos) throws Exception {
        List<Map<String, Object>> metas = new ArrayList<>();
        var b = multipart("/api/field/visits/" + appId);
        String now = Instant.now().toString();
        for (var e : photos.entrySet()) {
            String kind = e.getKey().split("#")[0];
            metas.add(Map.of("kind", kind, "takenAt", now, "lat", (Double) visit.getOrDefault("checkinLat", LAT), "lng", (Double) visit.getOrDefault("checkinLng", LNG)));
            b.file(new MockMultipartFile("photos", kind + ".jpg", "image/jpeg", e.getValue()));
        }
        Map<String, Object> v = new LinkedHashMap<>(visit);
        v.put("photos", metas);
        b.file(new MockMultipartFile("visit", "visit.json", "application/json", Json.write(v).getBytes()));
        return call(b, token, null);
    }

    Map<String, Object> visit(String clientId, double lat, double lng) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("clientId", clientId);
        v.put("checkinAt", Instant.now().minusSeconds(600).toString());
        v.put("checkinLat", lat);
        v.put("checkinLng", lng);
        v.put("checkinAccuracy", 12.0);
        v.put("capturedOffline", true);
        v.put("answers", new LinkedHashMap<>(Map.of("businessRunning", true, "ownerMet", true, "matchesType", true, "premises", "RENTED",
                "stockValue", 900000, "shopSizeSqft", 260, "footfall", "HIGH")));
        v.put("references", List.of(Map.of("name", "Neighbour Patil", "phone", "9800000001", "says", "Shop open for 8 years")));
        v.put("remarks", "Shop well stocked, owner present, steady customers during the visit");
        return v;
    }

    @Test
    @SuppressWarnings("unchecked")
    void fieldVisitFromTheAppIsCheckedAndCompletesTheStage() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), field = login("field1");
        long id = create(sales, kirana("Field Kirana", "FLDPS1004F", "9866000001"), "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode assigned = ok(get("/api/field/visits"), field, null).body();
        JsonNode mine = null;
        for (JsonNode x : assigned) if (x.get("applicationId").asLong() == id) mine = x;
        assertThat(mine).as(assigned.toString()).isNotNull();
        assertThat(mine.get("checklist").get("industry").asText()).isEqualTo("KIRANA");
        assertThat(mine.get("checklist").get("photos").toString()).contains("stock").contains("owner");
        assertThat(ok(get("/api/field/visits"), ops, null).body().toString()).doesNotContain("Field Kirana");

        double near = LAT + 0.0005;   // about 55 m north
        Map<String, byte[]> photos = new LinkedHashMap<>();
        photos.put("front", jpeg(1));
        photos.put("stock", jpeg(2));
        Res missing = sync(id, field, visit("visit-aaaa-0001", near, LNG), photos);
        assertThat(missing.body().get("code").asText()).isEqualTo("PHOTOS_MISSING");
        photos.put("owner", jpeg(3));
        Map<String, Object> noAnswer = visit("visit-aaaa-0001", near, LNG);
        ((Map<String, Object>) noAnswer.get("answers")).remove("stockValue");
        assertThat(sync(id, field, noAnswer, photos).body().get("code").asText()).isEqualTo("CHECKLIST_INCOMPLETE");
        assertThat(sync(id, sales, visit("visit-aaaa-0001", near, LNG), photos).body().get("code").asText()).isEqualTo("NOT_YOUR_STAGE");

        Res done = sync(id, field, visit("visit-aaaa-0001", near, LNG), photos);
        assertThat(done.status()).as(done.raw()).isEqualTo(200);
        assertThat(done.body().get("outcome").asText()).isEqualTo("POSITIVE");
        assertThat(done.body().get("locationCheck").asText()).isEqualTo("OK");
        assertThat(done.body().get("distanceM").asDouble()).isBetween(40.0, 70.0);
        assertThat(done.body().get("photoReuse").asBoolean()).isFalse();
        assertThat(ok(get("/api/applications/" + id), ops, null).body().get("states").get("FIELD").asText()).isEqualTo("DONE");
        // the same visit sent again (a retried sync) is not stored twice
        assertThat(sync(id, field, visit("visit-aaaa-0001", near, LNG), photos).body().get("duplicate").asBoolean()).isTrue();

        JsonNode visits = ok(get("/api/applications/" + id + "/field-visits"), ops, null).body();
        assertThat(visits.size()).isEqualTo(1);
        assertThat(visits.get(0).get("photos").size()).isEqualTo(3);
        assertThat(visits.get(0).get("photos").get(0).get("checks").toString()).contains("Photo reuse").contains("Not seen on any other application");
        long photoId = visits.get(0).get("photos").get(0).get("photo").get("id").asLong();
        MvcResult img = mvc.perform(get("/api/field/photos/" + photoId + "/content").header("Authorization", "Bearer " + ops)).andReturn();
        assertThat(img.getResponse().getStatus()).isEqualTo(200);
        assertThat(img.getResponse().getContentType()).isEqualTo("image/jpeg");

        // the visit's readings reach the kirana scorecard on the re-run decision
        String card = ok(get("/api/applications/" + id + "/decision"), ops, null).body().get("scorecardJson").asText();
        assertThat(card).contains("SC-KIRANA");
        assertThat(Json.MAPPER.readTree(card).get("missing").toString()).doesNotContain("Stock value / loan");
    }

    @Test
    void reusedPhotoAndFarCheckInAreFlagged() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), field = login("field1");
        long first = create(sales, kirana("First Shop", "FIRPS1004F", "9866000002"), "PAN", "AADHAAR", "UDYAM");
        long second = create(sales, kirana("Second Shop", "SECPS1004S", "9866000003"), "PAN", "AADHAAR", "UDYAM");
        ok(post("/api/applications/" + first + "/process"), ops, null);
        ok(post("/api/applications/" + second + "/process"), ops, null);
        Map<String, byte[]> photos = new LinkedHashMap<>(Map.of("front", jpeg(11), "stock", jpeg(12), "owner", jpeg(13)));
        assertThat(sync(first, field, visit("visit-bbbb-0001", LAT, LNG), photos).body().get("outcome").asText()).isEqualTo("POSITIVE");
        // the second shop uses the first shop's stock photo, and the officer checked in a kilometre away
        Map<String, byte[]> copied = new LinkedHashMap<>(Map.of("front", jpeg(21), "stock", jpeg(12), "owner", jpeg(23)));
        JsonNode r = sync(second, field, visit("visit-bbbb-0002", LAT + 0.01, LNG), copied).body();
        assertThat(r.get("photoReuse").asBoolean()).isTrue();
        assertThat(r.get("locationCheck").asText()).isEqualTo("FAR");
        assertThat(r.get("outcome").asText()).isEqualTo("REFER");
        assertThat(ok(get("/api/applications/" + second), ops, null).body().get("states").get("FRAUD").asText()).isEqualTo("REVIEW");
        String checks = ok(get("/api/applications/" + second + "/field-visits"), ops, null).body().toString();
        assertThat(checks).contains("already used on").contains("from the business location");
        // the visit id cannot be replayed onto another application
        assertThat(sync(second, field, visit("visit-bbbb-0001", LAT, LNG), photos).body().get("code").asText()).isEqualTo("CLIENT_ID_USED");
    }

    @Test
    void newTasksBecomeAlertsForTheOfficer() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        Res first = ok(get("/api/me/notifications"), ops, null);
        long before = first.body().get("unread").asLong();
        long id = create(sales, app("Alert Person", "ALEPN4411N", "9866000099", "400001"), "PAN", "AADHAAR");
        ok(post("/api/applications/" + id + "/submit"), sales, null);
        Res after = ok(get("/api/me/notifications"), ops, null);
        assertThat(after.body().get("unread").asLong()).isEqualTo(before + 1);
        JsonNode alert = null;
        for (JsonNode a : after.body().get("alerts")) if (a.path("applicationId").asLong() == id) alert = a;
        assertThat(alert).isNotNull();
        assertThat(alert.get("title").asText()).isEqualTo("Run automated checks");
        assertThat(alert.get("body").asText()).contains("Alert Person");

        ok(post("/api/me/push-token"), ops, Map.of("token", "ExponentPushToken[it-test-device]", "platform", "android"));
        assertThat(ok(get("/api/me/notifications"), ops, null).body().get("devices").asLong()).isEqualTo(1);
        assertThat(call(post("/api/me/push-token"), ops, Map.of("token", "")).status()).isEqualTo(422);

        ok(post("/api/me/notifications/read"), ops, Map.of("ids", List.of(alert.get("id").asLong())));
        assertThat(ok(get("/api/me/notifications"), ops, null).body().get("unread").asLong()).isEqualTo(before);
        ok(post("/api/me/notifications/read"), ops, Map.of());
        assertThat(ok(get("/api/me/notifications"), ops, null).body().get("unread").asLong()).isZero();
        ok(delete("/api/me/push-token").param("token", "ExponentPushToken[it-test-device]"), ops, null);
        assertThat(ok(get("/api/me/notifications"), ops, null).body().get("devices").asLong()).isZero();
    }
}
