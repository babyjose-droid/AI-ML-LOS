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

/** Phase 3A: loan book from the LMS feed (API, CSV, demo), SMA/NPA with the upgrade rule, ECL, early warning, disbursed loans joining. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.synthetic-book.enabled=true",
        "rhythm.synthetic-book.size=600",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-book",
        "rhythm.workflow.sla-initial-delay-ms=86400000",
        "rhythm.policy.activation-initial-delay-ms=86400000"
})
class LoanBookIT {

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

    @Test
    void loanBookFromDisbursementAndLmsFeed() throws Exception {
        String sales = staff("sales1"), ops = staff("ops1"), cm = staff("cm1"), cro = staff("cro1");
        LocalDate today = LocalDate.now();

        // 1. a loan disbursed here joins the book at once
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", "Book Person"); m.put("pan", "BOOPB1002B"); m.put("mobile", "9866000001");
        m.put("dob", today.minusYears(34).minusMonths(1).toString()); m.put("city", "Nashik"); m.put("address", "7 Book Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Test");
        m.put("bankAccountNo", "5010099887766"); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        JsonNode created = ok(post("/api/applications"), sales, m).body();
        long id = created.get("id").asLong();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        ok(post("/api/applications/" + id + "/process"), ops, null);
        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of());
        ok(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "123456"));
        assertThat(ok(post("/api/applications/" + id + "/disburse"), ops, null).body().get("status").asText()).isEqualTo("SUCCESS");
        String appNo = created.get("appNo").asText();
        JsonNode mine = ok(get("/api/book/loans?q=" + appNo), cro, null).body();
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("source").asText()).isEqualTo("RHYTHM");
        assertThat(mine.get(0).get("classification").asText()).isEqualTo("STANDARD");
        assertThat(mine.get(0).get("eclStage").asInt()).isEqualTo(1);
        assertThat(mine.get(0).get("outstanding").asDouble()).isEqualTo(450000);

        // 2. JSON feed: a loan new to the book (needs its details), a bad row, then NPA and the upgrade rule
        Map<String, Object> fresh = row("LMS-0001", 200000, 30000, 95);
        fresh.put("borrowerName", "Lms Borrower"); fresh.put("segment", "MSME"); fresh.put("productCode", "BL");
        fresh.put("sanctionedAmount", 300000); fresh.put("disbursedOn", today.minusMonths(10).toString()); fresh.put("tenureMonths", 36); fresh.put("rate", 20);
        Map<String, Object> incomplete = row("LMS-0002", 100000, 0, 0);
        LocalDate d1 = today.plusMonths(1);
        JsonNode f1 = ok(post("/api/book/feed"), ops, Map.of("feedDate", d1.toString(), "rows", List.of(fresh, incomplete, row("x", -1, 0, 0)))).body();
        assertThat(f1.get("accepted").asInt()).isEqualTo(1);
        assertThat(f1.get("rejected").asInt()).isEqualTo(2);
        assertThat(f1.get("errorsJson").asText()).contains("LMS-0002").contains("needs borrower name");
        JsonNode l = loan(cro, "LMS-0001");
        assertThat(l.get("source").asText()).isEqualTo("LMS");
        assertThat(l.get("classification").asText()).isEqualTo("NPA");
        assertThat(l.get("eclStage").asInt()).isEqualTo(3);
        assertThat(l.get("provisionIracp").asDouble()).isEqualTo(20000);
        assertThat(l.get("ewsScore").asInt()).isGreaterThanOrEqualTo(40);

        ok(post("/api/book/feed"), ops, Map.of("feedDate", d1.plusMonths(1).toString(), "rows", List.of(row("LMS-0001", 190000, 8000, 10))));
        assertThat(loan(cro, "LMS-0001").get("classification").asText()).as("arrears remain").isEqualTo("NPA");
        // an older feed for the same loan is refused
        JsonNode old = ok(post("/api/book/feed"), ops, Map.of("feedDate", d1.toString(), "rows", List.of(row("LMS-0001", 1, 0, 0)))).body();
        assertThat(old.get("rejected").asInt()).isEqualTo(1);

        // 3. CSV: arrears cleared -> upgraded to standard
        String csv = "loanRef,outstanding,overdue,dpd,bouncesLast3m,lastPaymentDate,paidEmis,status\n"
                + "LMS-0001,180000,0,0,1," + d1.plusMonths(2) + ",11,ACTIVE\n";
        MvcResult r = mvc.perform(multipart("/api/book/feed/csv").file(new MockMultipartFile("file", "lms.csv", "text/csv", csv.getBytes()))
                .param("feedDate", d1.plusMonths(2).toString()).header("Authorization", "Bearer " + ops)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        l = loan(cro, "LMS-0001");
        assertThat(l.get("classification").asText()).isEqualTo("STANDARD");
        assertThat(l.get("maxDpd").asInt()).isEqualTo(95);
        JsonNode detail = ok(get("/api/book/loans/" + l.get("id").asLong()), cro, null).body();
        assertThat(detail.get("history").size()).isEqualTo(3);
        MvcResult badCsv = mvc.perform(multipart("/api/book/feed/csv").file(new MockMultipartFile("file", "x.csv", "text/csv", "a,b\n1,2\n".getBytes()))
                .header("Authorization", "Bearer " + ops)).andReturn();
        assertThat(badCsv.getResponse().getStatus()).isEqualTo(422);

        // 4. demo feed seeds a book, then each run advances a month
        JsonNode demo1 = ok(post("/api/book/feed/demo"), ops, null).body();
        assertThat(demo1.get("accepted").asInt()).isGreaterThan(100);
        LocalDate dd1 = LocalDate.parse(demo1.get("feedDate").asText());
        assertThat(dd1).isEqualTo(d1.plusMonths(3));
        JsonNode demo2 = ok(post("/api/book/feed/demo"), ops, null).body();
        assertThat(LocalDate.parse(demo2.get("feedDate").asText())).isEqualTo(dd1.plusMonths(1));

        JsonNode s = ok(get("/api/book/summary"), cro, null).body();
        assertThat(s.get("loans").asInt()).isGreaterThan(100);
        assertThat(s.get("sources").get("DEMO").asInt()).isGreaterThan(100);
        assertThat(s.get("sources").get("LMS").asInt()).isEqualTo(1);
        assertThat(s.get("sources").get("RHYTHM").asInt()).isEqualTo(1);
        assertThat(s.get("ecl").asDouble()).isPositive();
        assertThat(s.get("classification").has("NPA")).isTrue();
        assertThat(s.get("stages").size()).isEqualTo(3);
        JsonNode watch = ok(get("/api/book/loans?watch=true"), cro, null).body();
        for (JsonNode w : watch) assertThat(w.get("ewsScore").asInt()).isGreaterThanOrEqualTo(40);
        assertThat(ok(get("/api/book/feeds"), cro, null).body().size()).isGreaterThanOrEqualTo(5);

        // roles
        assertThat(call(get("/api/book/summary"), sales, null).status()).isEqualTo(403);
        assertThat(call(post("/api/book/feed/demo"), cro, null).status()).isEqualTo(403);
    }
}
