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

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Phase 4A: Collateral AI end to end: declare, documents, EC, photos, valuations, rules, reviews, LTV cap at sanction, gold appraisal, unified decision, audit. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-collateral",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class CollateralIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    long app(String sales, String product, String name, String pan, String mobile, double amount, int tenure, String... docs) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", product); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(40).toString()); m.put("city", "Nashik"); m.put("address", "5 Temple Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 9); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", amount); m.put("tenureMonths", tenure); m.put("purpose", "Test");
        m.put("bankAccountNo", "50100" + mobile.substring(2)); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        long id = ok(post("/api/applications"), sales, m).body().get("id").asLong();
        for (String t : docs) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        return id;
    }

    Res doc(long col, String token, String type, Map<String, Object> fields, List<Map<String, Object>> tx) throws Exception {
        var b = multipart("/api/collaterals/" + col + "/documents").file(new MockMultipartFile("file", type.toLowerCase() + ".pdf", "application/pdf", ("%PDF-1.4 " + type).getBytes()))
                .param("docType", type).param("fields", Json.write(fields));
        if (tx != null) b.param("transactions", Json.write(tx));
        b.header("Authorization", "Bearer " + token);
        MvcResult r = mvc.perform(b).andReturn();
        String s = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), s.isEmpty() ? null : Json.MAPPER.readTree(s));
    }

    static byte[] jpeg(int seed) throws Exception {
        Random r = new Random(seed * 31L);
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < 240; y += 30) for (int x = 0; x < 320; x += 40) { g.setColor(new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256))); g.fillRect(x, y, 40, 30); }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    Res photo(long col, String token, String label, byte[] bytes, double lat, double lng) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/collaterals/" + col + "/media").file(new MockMultipartFile("file", label + ".jpg", "image/jpeg", bytes))
                .param("label", label).param("lat", String.valueOf(lat)).param("lng", String.valueOf(lng))
                .param("capturedAt", OffsetDateTime.now().minusHours(3).toString()).header("Authorization", "Bearer " + token)).andReturn();
        String s = r.getResponse().getContentAsString();
        return new Res(r.getResponse().getStatus(), s.isEmpty() ? null : Json.MAPPER.readTree(s));
    }

    static Map<String, Object> tx(String date, String type, String no, String from, String to, String linked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date); m.put("type", type); m.put("docNo", no); m.put("from", from); m.put("to", to); m.put("linkedRef", linked);
        return m;
    }

    static Map<String, Object> declare(String type, String owner, String survey, String municipal, double lat, double lng, double area) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type); m.put("ownerName", owner); m.put("address", "14 Gangapur Road, Nashik"); m.put("pincode", "422013");
        m.put("lat", lat); m.put("lng", lng); m.put("surveyNo", survey); m.put("municipalId", municipal); m.put("areaSqft", area);
        m.put("condition", "GOOD"); m.put("constructionStage", "COMPLETE"); m.put("occupancy", "SELF");
        return m;
    }

    static JsonNode rule(JsonNode detail, String id) {
        for (JsonNode h : detail.get("evaluation").get("rules")) if (id.equals(h.get("id").asText())) return h;
        throw new AssertionError(id);
    }

    static final List<String> RES_PHOTOS = List.of("EXTERIOR_FRONT", "EXTERIOR_SIDE", "INTERIOR_HALL", "INTERIOR_KITCHEN", "STREET_VIEW");

    @Test
    void propertyCollateralPassReferWithReviewsAndFail() throws Exception {
        String sales = staff("sales1"), valuer = staff("valuer1"), field = staff("field1"), legal = staff("legal1"), cm = staff("cm1"), comp = staff("comp1"), admin = staff("admin");
        String ecTo = LocalDate.now().minusDays(10).toString();

        // 1. residential: everything in order -> PASS
        long a1 = app(sales, "LAP", "Vikas Deshpande", "VIKPD1002V", "9877100001", 4_675_000, 120, "PAN", "AADHAAR", "UDYAM");
        assertThat(ok(get("/api/applications/" + a1 + "/unified-decision"), sales, null).body().get("collateral").asText()).isEqualTo("PENDING");
        assertThat(call(post("/api/applications/" + a1 + "/collaterals"), sales, Map.of("type", "VEHICLE")).status()).isEqualTo(422);
        long c1 = ok(post("/api/applications/" + a1 + "/collaterals"), sales, declare("RESIDENTIAL", "Vikas Deshpande", "CTS 1234/5", "NMC-44521", 20.0063, 73.7627, 1850)).body().get("id").asLong();
        assertThat(doc(c1, sales, "SALE_DEED", Map.of("owner", "Vikas Deshpande", "seller", "Anil Kulkarni", "surveyNo", "CTS1234/5", "areaSqft", 1850, "registrationDate", "2016-05-12"), null).status()).isEqualTo(200);
        doc(c1, sales, "EC", Map.of("periodFrom", "2012-01-01", "periodTo", ecTo, "surveyNo", "CTS1234/5"), List.of(
                tx("2016-05-12", "SALE", "4521/2016", "Anil Kulkarni", "Vikas Deshpande", null),
                tx("2016-06-20", "MORTGAGE", "4890/2016", "Vikas Deshpande", "State Bank of India", null),
                tx("2021-03-15", "RELEASE", "2210/2021", "State Bank of India", "Vikas Deshpande", "4890/2016")));
        doc(c1, sales, "KHATA", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC-44521"), null);
        doc(c1, sales, "PROPERTY_TAX", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC-44521"), null);
        doc(c1, sales, "VALUATION_REPORT", Map.of("marketValue", 8_500_000), null);
        assertThat(call(post("/api/collaterals/" + c1 + "/valuations"), sales, Map.of("valuer", "X", "marketValue", 1)).status()).isEqualTo(403);
        ok(post("/api/collaterals/" + c1 + "/valuations"), valuer, Map.of("valuer", "Er. P Deshmukh", "reportDate", LocalDate.now().minusDays(20).toString(), "marketValue", 8_500_000, "realisableValue", 7_650_000, "distressValue", 6_375_000));
        JsonNode mid = ok(get("/api/collaterals/" + c1), sales, null).body();
        assertThat(rule(mid, "CR-07").get("reason").asText()).contains("second empanelled valuation");
        ok(post("/api/collaterals/" + c1 + "/valuations"), valuer, Map.of("valuer", "Er. R Bhide", "reportDate", LocalDate.now().minusDays(15).toString(), "marketValue", 8_900_000));
        byte[] reused = jpeg(1);
        int i = 0;
        for (String l : RES_PHOTOS) assertThat(photo(c1, field, l, i++ == 0 ? reused : jpeg(i + 10), 20.0064, 73.7628).status()).isEqualTo(200);
        JsonNode d1 = ok(get("/api/collaterals/" + c1), sales, null).body();
        assertThat(d1.get("collateral").get("status").asText()).as(d1.get("evaluation").get("rules").toString()).isEqualTo("PASS");
        assertThat(d1.get("evaluation").get("ltv").get("maxExposure").asLong()).isEqualTo(4_972_500);
        assertThat(d1.get("evaluation").get("ltv").get("ltvRequested").asDouble()).isEqualTo(0.55);
        assertThat(d1.get("evaluation").get("ec").get("releasedCharges").size()).isEqualTo(1);
        assertThat(d1.get("evaluation").get("risk").size()).isEqualTo(10);
        assertThat(d1.get("evaluation").get("summary").asText()).contains("Status PASS").contains("[DOC-");
        assertThat(d1.get("summary").get("completeness").asDouble()).isEqualTo(1.0);
        JsonNode u1 = ok(get("/api/applications/" + a1 + "/unified-decision"), cm, null).body();
        assertThat(u1.get("collateral").asText()).isEqualTo("PASS");

        // 2. shop: open charge, CTS mismatch and a 25% valuation gap -> REFER; reviewers clear -> PASS with LTV cap
        long a2 = app(sales, "LAP", "Farhan Shaikh", "FARPS1002F", "9877100002", 3_200_000, 120, "PAN", "AADHAAR", "UDYAM");
        Map<String, Object> shop = declare("SHOP", "Farhan Shaikh", "CTS 778/2", "NMC-90211", 19.9975, 73.7898, 520);
        long c2 = ok(post("/api/applications/" + a2 + "/collaterals"), sales, shop).body().get("id").asLong();
        doc(c2, sales, "SALE_DEED", Map.of("owner", "Farhan Shaikh", "seller", "Kishor Jain", "surveyNo", "CTS778/2", "areaSqft", 520, "registrationDate", "2014-08-02"), null);
        doc(c2, sales, "EC", Map.of("periodFrom", "2012-04-01", "periodTo", ecTo, "surveyNo", "CTS778/3"), List.of(
                tx("2014-08-02", "SALE", "3301/2014", "Kishor Jain", "Farhan Shaikh", null),
                tx("2019-02-11", "MORTGAGE", "1187/2019", "Farhan Shaikh", "Janata Sahakari Bank", null)));
        doc(c2, sales, "KHATA", Map.of("owner", "Farhan Shaikh", "municipalId", "NMC-90211"), null);
        doc(c2, sales, "PROPERTY_TAX", Map.of("owner", "Farhan Shaikh", "municipalId", "NMC-90211"), null);
        doc(c2, sales, "VALUATION_REPORT", Map.of("marketValue", 4_950_000), null);
        ok(post("/api/collaterals/" + c2 + "/valuations"), valuer, Map.of("valuer", "Er. S Kale", "reportDate", LocalDate.now().minusDays(30).toString(), "marketValue", 4_950_000));
        ok(post("/api/collaterals/" + c2 + "/valuations"), valuer, Map.of("valuer", "Er. M Patwardhan", "reportDate", LocalDate.now().minusDays(12).toString(), "marketValue", 6_187_500));
        i = 0;
        for (String l : List.of("EXTERIOR_FRONT", "SIGNAGE", "INTERIOR_HALL", "STREET_VIEW", "NEIGHBOURHOOD")) photo(c2, field, l, jpeg(100 + i++), 19.9976, 73.7899);
        JsonNode d2 = ok(get("/api/collaterals/" + c2), sales, null).body();
        assertThat(d2.get("collateral").get("status").asText()).isEqualTo("REFER");
        assertThat(rule(d2, "CR-04").get("reason").asText()).contains("1187/2019");
        assertThat(rule(d2, "CR-05").get("passed").asBoolean()).isFalse();
        assertThat(rule(d2, "CR-08").get("reason").asText()).contains("25.0%");
        assertThat(d2.get("collateral").get("ltvCap").asBoolean()).isTrue();

        JsonNode legalQueue = ok(get("/api/collateral-reviews"), legal, null).body();
        assertThat(legalQueue.size()).isEqualTo(2);
        JsonNode valuerQueue = ok(get("/api/collateral-reviews"), valuer, null).body();
        assertThat(valuerQueue.size()).isEqualTo(1);
        long legalCase = legalQueue.get(0).get("review").get("id").asLong();
        assertThat(call(post("/api/collateral-reviews/" + legalCase + "/resolve"), valuer, Map.of("outcome", "CLEARED", "note", "Not my queue but trying anyway")).status()).isEqualTo(403);
        assertThat(call(post("/api/collateral-reviews/" + legalCase + "/resolve"), legal, Map.of("outcome", "CLEARED", "note", "ok")).status()).isEqualTo(422);
        for (JsonNode r : legalQueue)
            ok(post("/api/collateral-reviews/" + r.get("review").get("id").asLong() + "/resolve"), legal, Map.of("outcome", "CLEARED",
                    "note", "Release letter from Janata Sahakari Bank seen; CTS 778/3 is a typing error in the EC (sub-registrar letter on file)"));
        ok(post("/api/collateral-reviews/" + valuerQueue.get(0).get("review").get("id").asLong() + "/resolve"), valuer, Map.of("outcome", "CLEARED",
                "note", "Second report used income capitalisation on an optimistic rent; the lower value is used"));
        JsonNode after = ok(get("/api/collaterals/" + c2), sales, null).body();
        assertThat(after.get("collateral").get("status").asText()).isEqualTo("PASS");
        assertThat(after.get("evaluation").get("ltv").get("maxExposure").asLong()).isEqualTo(2_314_125);
        assertThat(after.get("evaluation").get("conditions").toString()).contains("LTV CAP");
        assertThat(rule(after, "CR-04").get("ruling").asText()).isEqualTo("CLEARED");
        assertThat(ok(get("/api/collateral-reviews"), legal, null).body().size()).isZero();

        // 3. plot: EC shows a sale to a third party, a reused photo and photos taken 2.3 km away -> FAIL
        long a3 = app(sales, "LAP", "Rajesh Gupta", "RAJPG1004G", "9877100003", 2_500_000, 120, "PAN", "AADHAAR", "UDYAM");
        Map<String, Object> plot = declare("PLOT", "Rajesh Gupta", "GATA 512", "KNN-7781", 26.4710, 80.2410, 4000);
        plot.put("pincode", "208023");
        long c3 = ok(post("/api/applications/" + a3 + "/collaterals"), sales, plot).body().get("id").asLong();
        doc(c3, sales, "EC", Map.of("periodFrom", "1995-01-01", "periodTo", ecTo, "surveyNo", "GATA512"), List.of(
                tx("2018-11-20", "SALE", "6612/2018", "Om Prakash Singh", "Rajesh Gupta", null),
                tx("2024-07-09", "SALE", "5120/2024", "Rajesh Gupta", "Manoj Verma", null)));
        Res p = photo(c3, field, "PLOT_FRONT", reused, 26.4917, 80.2410);
        assertThat(p.body().get("reuseMatch").asText()).startsWith("COL-");
        assertThat(p.body().get("distanceM").asDouble()).isGreaterThan(2000);
        JsonNode d3 = ok(get("/api/collaterals/" + c3), sales, null).body();
        assertThat(d3.get("collateral").get("status").asText()).isEqualTo("FAIL");
        assertThat(rule(d3, "CR-01").get("reason").asText()).contains("Manoj Verma");
        assertThat(rule(d3, "CR-10").get("passed").asBoolean()).isFalse();
        assertThat(d3.get("evaluation").get("title").size()).isEqualTo(2);
        JsonNode u3 = ok(get("/api/applications/" + a3 + "/unified-decision"), cm, null).body();
        assertThat(u3.get("decision").asText()).isEqualTo("REJECT");

        // manipulation confirmed by a legal reviewer fails the collateral; a sales user cannot do it
        long docId = d1.get("documents").get(0).get("document").get("id").asLong();
        assertThat(call(post("/api/collateral-documents/" + docId + "/manipulated"), sales, Map.of("note", "Looks edited to me")).status()).isEqualTo(403);

        // overview, audit trail, policy, alerts
        JsonNode ov = ok(get("/api/collaterals"), cm, null).body();
        assertThat(ov.get("collaterals").size()).isGreaterThanOrEqualTo(3);
        assertThat(ov.get("byStatus").toString()).contains("PASS").contains("FAIL");
        JsonNode trail = ok(get("/api/collaterals/" + c2 + "/audit"), comp, null).body();
        assertThat(trail.get("evidence").size()).isGreaterThan(8);
        assertThat(trail.get("reviews").toString()).contains("CLEARED").contains("legal1");
        assertThat(call(get("/api/collaterals/" + c2 + "/audit"), sales, null).status()).isEqualTo(403);
        assertThat(call(put("/api/collateral-policy/RESIDENTIAL"), admin, Map.of("maxLtv", 0.7)).status()).isEqualTo(422);
        assertThat(ok(put("/api/collateral-policy/RESIDENTIAL"), admin, Map.of("maxLtv", 0.7, "note", "Board approval 14 Oct 2026")).body().get("maxLtv").asDouble()).isEqualTo(0.7);
        assertThat(call(put("/api/collateral-policy/GOLD"), admin, Map.of("maxLtv", 0.9, "note", "Board approval 14 Oct 2026")).status()).isEqualTo(422);
        ok(put("/api/collateral-policy/RESIDENTIAL"), admin, Map.of("maxLtv", 0.65, "note", "Back to the board policy"));
        ok(get("/api/collateral-alerts"), cm, null);
    }

    @Test
    void goldAppraisalUnlocksTheWorkflowAndCapsTheSanction() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), co = staff("co1"), cm = staff("cm1");
        long a = app(sales, "GL", "Asha Kulkarni", "ASHPK1002K", "9877100009", 300_000, 12, "PAN", "AADHAAR");
        long c = ok(post("/api/applications/" + a + "/collaterals"), sales, Map.of("type", "GOLD", "description", "2 bangles, 1 chain")).body().get("id").asLong();
        assertThat(call(post("/api/collaterals/" + c + "/gold-appraisal"), sales, Map.of("items", List.of())).status()).isEqualTo(403);
        assertThat(call(post("/api/collaterals/" + c + "/gold-appraisal"), co, Map.of("items", List.of(Map.of("grossWeight", 10, "purityKarat", 30)))).status()).isEqualTo(422);
        JsonNode rates = ok(get("/api/collateral-rates"), co, null).body();
        double rate = rates.get("goldRateUsed").asDouble();
        assertThat(rate).isGreaterThan(10000);
        // value about ₹3 lakh: 27 g net of 22 carat
        JsonNode v = ok(post("/api/collaterals/" + c + "/gold-appraisal"), co, Map.of("items", List.of(
                Map.of("description", "Bangles", "grossWeight", 20, "stoneWeight", 0, "purityKarat", 22),
                Map.of("description", "Chain", "grossWeight", 8, "stoneWeight", 1, "purityKarat", 22)))).body();
        assertThat(v.get("source").asText()).isEqualTo("APPRAISAL");
        JsonNode d = ok(get("/api/collaterals/" + c), co, null).body();
        assertThat(d.get("collateral").get("status").asText()).isEqualTo("PASS");
        double value = v.get("marketValue").asDouble();
        assertThat(value).isCloseTo(27 * rate, org.assertj.core.data.Offset.offset(2.0));
        // RBI tier: 80% for loans of ₹2.5 lakh to ₹5 lakh
        assertThat(d.get("evaluation").get("ltv").get("maxLtv").asDouble()).isEqualTo(0.80);
        long cap = d.get("evaluation").get("ltv").get("maxExposure").asLong();
        assertThat(cap).isEqualTo((long) Math.floor(value * 0.80 + 1e-6));

        ok(post("/api/applications/" + a + "/process"), ops, null);
        JsonNode dec = ok(get("/api/applications/" + a + "/decision"), ops, null).body();
        JsonNode u = ok(get("/api/applications/" + a + "/unified-decision"), cm, null).body();
        assertThat(u.get("collateral").asText()).isEqualTo("PASS");
        if (dec.get("decision").asText().startsWith("APPROVE")) {
            assertThat(u.get("decision").asText()).isEqualTo("APPROVE_WITH_CONDITIONS");
            assertThat(u.get("conditions").toString()).contains("sealed");
            double rec = dec.get("recommendedAmount").asDouble();
            if (rec > cap) {
                Res over = call(post("/api/applications/" + a + "/sanction"), cm, Map.of());
                assertThat(over.status()).as(String.valueOf(over.body())).isEqualTo(422);
                assertThat(over.body().get("code").asText()).isEqualTo("LTV_EXCEPTION_REASON");
            }
            JsonNode s = ok(post("/api/applications/" + a + "/sanction"), cm, Map.of("amount", Math.min(cap, rec), "note", "Within the gold LTV cap after appraisal")).body();
            assertThat(s.get("conditionsJson").asText()).contains("sealed");
        }
    }

    @Test
    void liveLoanCollateralIsReleasedOrSwappedOnlyWhenTheLoanStaysCovered() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), valuer = staff("valuer1"), cm = staff("cm1"), admin = staff("admin");
        long a = app(sales, "LAP", "Lien Holder", "LIEPH1002L", "9877100091", 3_000_000, 120, "PAN", "AADHAAR", "UDYAM");
        long house = ok(post("/api/applications/" + a + "/collaterals"), sales, declare("RESIDENTIAL", "Lien Holder", "CTS 99/1", "NMC-111", 20.0063, 73.7627, 1850)).body().get("id").asLong();
        long shop = ok(post("/api/applications/" + a + "/collaterals"), sales, declare("SHOP", "Lien Holder", "CTS 99/2", "NMC-112", 19.9975, 73.7898, 520)).body().get("id").asLong();
        ok(post("/api/collaterals/" + house + "/valuations"), valuer, Map.of("valuer", "Er. P Deshmukh", "reportDate", LocalDate.now().minusDays(5).toString(), "marketValue", 6_000_000));
        ok(post("/api/collaterals/" + shop + "/valuations"), valuer, Map.of("valuer", "Er. P Deshmukh", "reportDate", LocalDate.now().minusDays(5).toString(), "marketValue", 2_000_000));

        // automated valuation from recent registered sales nearby (imported here so the test does not age with the seeded rows)
        JsonNode imp = ok(post("/api/collateral/comparables"), valuer, Map.of("source", "REGISTRY", "rows", List.of(
                Map.of("type", "RESIDENTIAL", "pincode", "422013", "areaSqft", 1200, "price", 6_500_000, "saleDate", LocalDate.now().minusMonths(1).toString(), "lat", 20.006, "lng", 73.763),
                Map.of("type", "RESIDENTIAL", "pincode", "422013", "areaSqft", 1700, "price", 9_100_000, "saleDate", LocalDate.now().minusMonths(3).toString(), "lat", 20.007, "lng", 73.761),
                Map.of("type", "RESIDENTIAL", "pincode", "422013", "areaSqft", 2000, "price", 10_600_000, "saleDate", LocalDate.now().minusMonths(5).toString(), "lat", 20.005, "lng", 73.764),
                Map.of("type", "RESIDENTIAL", "pincode", "42201", "areaSqft", 1200, "price", 6_500_000, "saleDate", "2026-01-01")))).body();
        assertThat(imp.get("added").asInt()).isEqualTo(3);
        assertThat(imp.get("errors").toString()).contains("PIN code");
        JsonNode avm = ok(get("/api/collaterals/" + house), sales, null).body().get("avm");
        assertThat(avm.get("comparables").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(avm.get("value").asDouble()).isBetween(1850 * 3500.0, 1850 * 8000.0);
        assertThat(avm.get("basis").asText()).contains("sales within");
        assertThat(ok(get("/api/collateral/comparables"), cm, null).body().size()).isGreaterThan(20);

        // the loan is disbursed (set directly: this test is about what happens after)
        jdbc.update("update loan_application set app_state = 'DISBURSED' where id = ?", a);
        jdbc.update("""
                insert into live_loan (loan_ref, application_id, app_no, borrower_name, segment, product_code, branch_id, sanctioned_amount, disbursed_on, tenure_months,
                  rate, emi, secured, source, status, outstanding)
                select 'LN-LIEN-1', id, app_no, applicant_name, segment, product_code, branch_id, 3000000, current_date - 400, 120, 13.5, 45000, true, 'RHYTHM', 'ACTIVE', 3000000
                from loan_application where id = ?""", a);
        jdbc.update("update collateral set lien_state = 'ACTIVE' where application_id = ?", a);
        long loan = jdbc.queryForObject("select id from live_loan where loan_ref = 'LN-LIEN-1'", Long.class);

        JsonNode view = ok(get("/api/book/loans/" + loan + "/collateral"), ops, null).body();
        assertThat(view.get("ltv").asDouble()).isEqualTo(0.375);
        Map<Long, JsonNode> byId = new HashMap<>();
        for (JsonNode c : view.get("collaterals")) byId.put(c.get("id").asLong(), c);
        assertThat(byId.get(house).get("canRelease").asBoolean()).isFalse();
        assertThat(byId.get(shop).get("canRelease").asBoolean()).isTrue();

        // the house carries the loan: it cannot go while 30 lakh is outstanding; the shop can (part release)
        Res noHouse = call(post("/api/book/loans/" + loan + "/collateral/release"), ops, Map.of("collateralId", house, "reason", "Borrower wants the house back"));
        assertThat(noHouse.status()).isEqualTo(422);
        assertThat(noHouse.body().get("code").asText()).isEqualTo("COVER_SHORT");
        long rel = ok(post("/api/book/loans/" + loan + "/collateral/release"), ops, Map.of("collateralId", shop, "reason", "Part-payment made, shop to be sold")).body().get("id").asLong();
        assertThat(call(post("/api/book/loans/" + loan + "/collateral/release"), ops, Map.of("collateralId", shop, "reason", "Asking for the shop again")).body().get("code").asText()).isEqualTo("ALREADY_REQUESTED");
        assertThat(call(post("/api/collateral-changes/" + rel + "/decision"), ops, Map.of("approve", true, "note", "Approving my own request")).status()).isEqualTo(403);
        assertThat(call(post("/api/collateral-changes/" + rel + "/complete"), ops, Map.of("handedTo", "Lien Holder")).body().get("code").asText()).isEqualTo("NOT_APPROVED");
        ok(post("/api/collateral-changes/" + rel + "/decision"), cm, Map.of("approve", true, "note", "House alone covers the loan at 50% LTV"));
        JsonNode done = ok(post("/api/collateral-changes/" + rel + "/complete"), ops, Map.of("handedTo", "Lien Holder in person")).body();
        assertThat(done.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("letter").asText()).contains("Release of security").contains("remaining collateral");
        assertThat(jdbc.queryForObject("select lien_state from collateral where id = ?", String.class, shop)).isEqualTo("RELEASED");
        assertThat(doc(shop, sales, "EC", Map.of("periodFrom", "2012-01-01"), null).status()).isEqualTo(409);

        // substitution: a flat offered in place of the house must pass and carry the loan
        long flat = ok(post("/api/book/loans/" + loan + "/collateral/" + house + "/substitute"), ops, declare("RESIDENTIAL", "Lien Holder", "CTS 12/7", "NMC-777", 20.0059, 73.7741, 1500)).body().get("id").asLong();
        ok(post("/api/collaterals/" + flat + "/valuations"), valuer, Map.of("valuer", "Er. R Bhide", "reportDate", LocalDate.now().toString(), "marketValue", 5_000_000));
        Res notCleared = call(post("/api/book/loans/" + loan + "/collateral/substitution"), ops, Map.of("collateralId", house, "newCollateralId", flat, "reason", "Selling the house, offering the flat"));
        assertThat(notCleared.body().get("code").asText()).isEqualTo("SUBSTITUTE_NOT_CLEARED");
        jdbc.update("update collateral set status = 'PASS' where id = ?", flat);
        long sub = ok(post("/api/book/loans/" + loan + "/collateral/substitution"), ops, Map.of("collateralId", house, "newCollateralId", flat, "reason", "Selling the house, offering the flat"))
                .body().get("id").asLong();
        ok(post("/api/collateral-changes/" + sub + "/decision"), cm, Map.of("approve", true, "note", "Flat covers 32.5 lakh at 65% LTV"));
        assertThat(ok(post("/api/collateral-changes/" + sub + "/complete"), ops, Map.of("handedTo", "Lien Holder in person")).body().get("letter").asText()).contains("replaced by");
        assertThat(jdbc.queryForObject("select lien_state from collateral where id = ?", String.class, house)).isEqualTo("SUBSTITUTED");
        assertThat(jdbc.queryForObject("select lien_state from collateral where id = ?", String.class, flat)).isEqualTo("ACTIVE");

        // closure: release is due within 30 days; five days late costs 25,000
        jdbc.update("update live_loan set outstanding = 0, status = 'CLOSED', last_payment_date = current_date - 35 where id = ?", loan);
        JsonNode closed = ok(get("/api/book/loans/" + loan + "/collateral"), ops, null).body();
        assertThat(closed.get("releaseOverdueDays").asInt()).isEqualTo(5);
        JsonNode auto = ok(post("/api/book/loans/" + loan + "/collateral/release"), ops, Map.of("collateralId", flat, "reason", "Loan closed, documents back")).body();
        assertThat(auto.get("status").asText()).isEqualTo("APPROVED");
        JsonNode late = ok(post("/api/collateral-changes/" + auto.get("id").asLong() + "/complete"), ops, Map.of("handedTo", "Lien Holder in person")).body();
        assertThat(late.get("daysLate").asInt()).isEqualTo(5);
        assertThat(late.get("compensation").asDouble()).isEqualTo(25_000);
        assertThat(late.get("letter").asText()).contains("fully repaid").contains("5 days");
        assertThat(ok(get("/api/audit?applicationId=" + a), admin, null).body().toString()).contains("collateral.released").contains("collateral.substituted");
    }
}
