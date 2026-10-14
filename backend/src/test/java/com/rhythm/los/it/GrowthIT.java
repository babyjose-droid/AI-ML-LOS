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

/** Phase 3B: restructuring (maker-checker, NPA until the specified period), collections (contact hours, promises to pay), top-ups, rate simulator. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=300",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-growth",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class GrowthIT {

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

    String staff(String u) throws Exception {
        return ok(post("/api/auth/login"), null, Map.of("username", u, "password", "Rhythm@123")).body().get("token").asText();
    }

    static Map<String, Object> row(String ref, double outstanding, double overdue, int dpd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanRef", ref); m.put("outstanding", outstanding); m.put("overdue", overdue); m.put("dpd", dpd);
        return m;
    }

    JsonNode loan(String token, String ref) throws Exception {
        JsonNode l = ok(get("/api/book/loans?q=" + ref), token, null).body();
        assertThat(l.size()).as(ref).isEqualTo(1);
        return l.get(0);
    }

    static final java.time.ZoneId IST = java.time.ZoneId.of("Asia/Kolkata");

    Map<String, Object> newLoan(String ref, String segment, double outstanding, double overdue, int dpd) {
        Map<String, Object> r = row(ref, outstanding, overdue, dpd);
        r.put("borrowerName", "Borrower " + ref); r.put("segment", segment); r.put("productCode", "BL");
        r.put("sanctionedAmount", 300000); r.put("disbursedOn", LocalDate.now().minusMonths(10).toString()); r.put("tenureMonths", 36); r.put("rate", 20);
        return r;
    }

    void feed(String ops, LocalDate on, Object... rows) throws Exception {
        JsonNode f = ok(post("/api/book/feed"), ops, Map.of("feedDate", on.toString(), "rows", List.of(rows))).body();
        assertThat(f.get("rejected").asInt()).as(String.valueOf(f)).isZero();
    }

    @Test
    void restructuringIsMakerCheckerAndKeepsTheLoanNpaForTheSpecifiedPeriod() throws Exception {
        String ops = staff("ops1"), cm = staff("cm1"), cro = staff("cro1");
        LocalDate d0 = LocalDate.now();
        feed(ops, d0, newLoan("RS-0001", "MSME", 220000, 22000, 45));
        long id = loan(cro, "RS-0001").get("id").asLong();
        assertThat(loan(cro, "RS-0001").get("classification").asText()).isEqualTo("SMA-1");

        assertThat(call(post("/api/book/loans/" + id + "/restructure"), cm, Map.of("kind", "RATE_REDUCTION", "newRate", 25, "reason", "x")).status()).isEqualTo(422);
        assertThat(call(post("/api/book/loans/" + id + "/restructure"), cm, Map.of("kind", "TENURE_EXTENSION", "newTenureMonths", 5, "reason", "x")).status()).isEqualTo(422);
        JsonNode p = ok(post("/api/book/loans/" + id + "/restructure"), cm, Map.of("kind", "MORATORIUM", "moratoriumMonths", 2, "newTenureMonths", 40,
                "newRate", 18, "reason", "Shop closed for two months after a flood")).body();
        assertThat(p.get("status").asText()).isEqualTo("PENDING");
        assertThat(Json.MAPPER.readTree(p.get("scheduleJson").asText()).size()).isEqualTo(40);
        assertThat(call(post("/api/book/loans/" + id + "/restructure"), cm, Map.of("kind", "MORATORIUM", "moratoriumMonths", 1, "reason", "again")).status()).isEqualTo(409);
        assertThat(ok(get("/api/restructures/pending"), cro, null).body().toString()).contains("RS-0001");

        long pid = p.get("id").asLong();
        assertThat(call(post("/api/restructures/" + pid + "/approve"), ops, Map.of()).status()).isEqualTo(403);
        Res self = call(post("/api/restructures/" + pid + "/approve"), cm, Map.of());
        assertThat(self.status()).isEqualTo(403);
        assertThat(self.body().get("code").asText()).isEqualTo("MAKER_CHECKER");
        ok(post("/api/restructures/" + pid + "/approve"), cro, Map.of("note", "Temporary; business viable"));
        JsonNode l = loan(cro, "RS-0001");
        assertThat(l.get("restructured").asBoolean()).isTrue();
        assertThat(l.get("classification").asText()).isEqualTo("NPA");
        assertThat(l.get("eclStage").asInt()).isEqualTo(3);
        assertThat(l.get("overdue").asDouble()).isZero();
        assertThat(l.get("rate").asDouble()).isEqualTo(18);
        assertThat(call(post("/api/restructures/" + pid + "/reject"), cro, Map.of()).status()).isEqualTo(409);

        // paying on time does not upgrade it within the specified period (moratorium + 1 + 12 months)...
        feed(ops, d0.plusMonths(6), row("RS-0001", 215000, 0, 0));
        assertThat(loan(cro, "RS-0001").get("classification").asText()).isEqualTo("NPA");
        // ...but it does after
        feed(ops, d0.plusMonths(16), row("RS-0001", 180000, 0, 0));
        assertThat(loan(cro, "RS-0001").get("classification").asText()).isEqualTo("STANDARD");
        assertThat(loan(cro, "RS-0001").get("eclStage").asInt()).as("restructured stays stage 2").isEqualTo(2);
    }

    @Test
    void collectionsQueueContactHoursAndPromisesToPay() throws Exception {
        String ops = staff("ops1"), field = staff("field1"), sales = staff("sales1");
        LocalDate d0 = LocalDate.now();
        feed(ops, d0, newLoan("CL-0001", "MSME", 150000, 10000, 20), newLoan("CL-0002", "MSME", 150000, 10000, 20));
        JsonNode q = ok(get("/api/collections/queue"), ops, null).body();
        JsonNode mine = null;
        for (JsonNode x : q) if ("CL-0001".equals(x.get("loanRef").asText())) mine = x;
        assertThat(mine).isNotNull();
        assertThat(mine.get("recommendation").get("channel").asText()).isEqualTo("CALL");
        assertThat(mine.get("recommendation").get("bestTime").asText()).contains("11 am");
        long id1 = mine.get("loanId").asLong();
        long id2 = loan(ops, "CL-0002").get("id").asLong();

        LocalDate y = LocalDate.now(IST).minusDays(1);
        String night = y.atTime(21, 0).atZone(IST).toOffsetDateTime().toString(), morning = y.atTime(11, 0).atZone(IST).toOffsetDateTime().toString();
        Res late = call(post("/api/collections/loans/" + id1 + "/contacts"), field, Map.of("channel", "CALL", "outcome", "NO_ANSWER", "contactedAt", night));
        assertThat(late.status()).isEqualTo(422);
        assertThat(late.body().get("code").asText()).isEqualTo("CONTACT_HOURS");
        // an SMS at night is not a call or a visit
        ok(post("/api/collections/loans/" + id1 + "/contacts"), ops, Map.of("channel", "SMS", "outcome", "SENT", "contactedAt", night));
        assertThat(call(post("/api/collections/loans/" + id1 + "/contacts"), field, Map.of("channel", "CALL", "outcome", "PROMISE_TO_PAY", "contactedAt", morning)).status()).isEqualTo(422);
        ok(post("/api/collections/loans/" + id1 + "/contacts"), field, Map.of("channel", "CALL", "outcome", "PROMISE_TO_PAY", "contactedAt", morning,
                "ptpDate", y.plusDays(5).toString(), "ptpAmount", 10000));
        ok(post("/api/collections/loans/" + id2 + "/contacts"), field, Map.of("channel", "CALL", "outcome", "PROMISE_TO_PAY", "contactedAt", morning,
                "ptpDate", y.plusDays(5).toString(), "ptpAmount", 10000));
        assertThat(ok(get("/api/collections/loans/" + id1), ops, null).body().get("openPromise").get("ptpStatus").asText()).isEqualTo("OPEN");

        // next feed: CL-0001 paid on day 2, CL-0002 did not
        Map<String, Object> paid = row("CL-0001", 145000, 0, 0);
        paid.put("lastPaymentDate", y.plusDays(2).toString());
        feed(ops, d0.plusMonths(1), paid, row("CL-0002", 150000, 15000, 50));
        JsonNode c1 = ok(get("/api/collections/loans/" + id1), ops, null).body();
        assertThat(c1.get("ptpKept").asInt()).isEqualTo(1);
        assertThat(c1.get("contacts").size()).isEqualTo(2);
        JsonNode c2 = ok(get("/api/collections/loans/" + id2), ops, null).body();
        assertThat(c2.get("ptpBroken").asInt()).isEqualTo(1);
        assertThat(c2.get("recommendation").get("channel").asText()).isEqualTo("FIELD_VISIT");
        assertThat(c2.get("recommendation").get("bestTime").asText()).startsWith("11:00–12:00");
        JsonNode st = ok(get("/api/collections/stats"), ops, null).body();
        assertThat(st.get("keptRate").asDouble()).isEqualTo(0.5);
        assertThat(call(get("/api/collections/queue"), sales, null).status()).isEqualTo(403);
    }

    @Test
    void topupOfferBecomesAPrefilledDraftAndRatesCanBeSimulated() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), cm = staff("cm1"), cro = staff("cro1");
        LocalDate today = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", "Topup Person"); m.put("pan", "TOPPT1002T"); m.put("mobile", "9877000001");
        m.put("dob", today.minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "9 Topup Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test");
        m.put("bankAccountNo", "5010011223344"); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        JsonNode created = ok(post("/api/applications"), sales, m).body();
        long app = created.get("id").asLong();
        String appNo = created.get("appNo").asText();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + app + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        ok(post("/api/applications/" + app + "/process"), ops, null);
        ok(post("/api/applications/" + app + "/sanction"), cm, Map.of());
        ok(post("/api/applications/" + app + "/kfs/accept"), sales, Map.of("otp", "123456"));
        ok(post("/api/applications/" + app + "/disburse"), ops, null);
        JsonNode l = loan(cro, appNo);
        String ref = l.get("loanRef").asText();
        long loanId = l.get("id").asLong();

        // too new for a top-up
        assertThat(ok(get("/api/topups/loans/" + loanId), sales, null).body().get("eligible").asBoolean()).isFalse();
        assertThat(call(post("/api/topups/loans/" + loanId + "/offer"), sales, null).status()).isEqualTo(422);

        // seven months of clean repayment
        Map<String, Object> clean = row(ref, 300000, 0, 0);
        clean.put("paidEmis", 7); clean.put("bouncesLast3m", 0); clean.put("lastPaymentDate", today.plusMonths(7).toString());
        feed(ops, today.plusMonths(7), clean);
        JsonNode chk = ok(get("/api/topups/loans/" + loanId), sales, null).body();
        assertThat(chk.get("eligible").asBoolean()).as(String.valueOf(chk.get("reasons"))).isTrue();
        assertThat(chk.get("amount").asDouble()).isEqualTo(370000);
        assertThat(ok(get("/api/topups/eligible"), sales, null).body().toString()).contains(ref);
        JsonNode offer = ok(post("/api/topups/loans/" + loanId + "/offer"), sales, null).body();
        assertThat(call(post("/api/topups/loans/" + loanId + "/offer"), sales, null).status()).isEqualTo(409);
        JsonNode applied = ok(post("/api/topups/offers/" + offer.get("id").asLong() + "/apply"), sales, null).body();
        long draft = applied.get("applicationId").asLong();
        JsonNode d = ok(get("/api/applications/" + draft), sales, null).body();
        assertThat(d.toString()).contains("DRAFT").contains("Top-up on loan " + ref).contains("370000");
        assertThat(call(post("/api/topups/offers/" + offer.get("id").asLong() + "/apply"), sales, null).status()).isEqualTo(409);

        // rate simulator over the book
        feed(ops, today, newLoan("RT-0001", "MSME", 250000, 0, 0), newLoan("RT-0002", "SALARIED", 150000, 0, 0));
        JsonNode sim = ok(post("/api/pricing/simulate"), cro, Map.of("changeBps", 100)).body();
        assertThat(sim.get("mixLoans").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(sim.get("scenario").get("volume").asDouble()).isLessThan(sim.get("base").get("volume").asDouble());
        assertThat(sim.get("scenario").get("yield").asDouble()).isGreaterThan(sim.get("base").get("yield").asDouble());
        assertThat(sim.get("curve").size()).isEqualTo(13);
        assertThat(ok(post("/api/pricing/simulate"), cro, Map.of("changeBps", -50, "productCode", "PL")).body().get("mixLoans").asInt()).isEqualTo(1);
        assertThat(call(post("/api/pricing/simulate"), cro, Map.of("changeBps", 900)).status()).isEqualTo(422);
        assertThat(call(post("/api/pricing/simulate"), sales, Map.of("changeBps", 100)).status()).isEqualTo(403);
    }
}
