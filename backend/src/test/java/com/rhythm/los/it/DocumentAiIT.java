package com.rhythm.los.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.document.DocumentReader;
import com.rhythm.los.document.DocumentReading;
import com.rhythm.los.document.DocumentReading.FieldValue;
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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Phase 2 journey: AI document reading, human review, Aadhaar masking and KYC derived from documents.
 * The AI service is replaced by a stub that returns what the real service returns for the specimen cards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-ai",
        "rhythm.document-ai.url=http://ai-stub.invalid"
})
class DocumentAiIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>("postgres:16-alpine");

    static final byte[] UNLOCKED_PDF = "%PDF-1.4 unlocked statement %%EOF".getBytes(StandardCharsets.UTF_8);
    static final byte[] LOCKED_PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<</Root 1 0 R/Encrypt 5 0 R>>\n%%EOF".getBytes(StandardCharsets.UTF_8);
    static final byte[] MASKED_PNG = "masked-image-bytes".getBytes(StandardCharsets.UTF_8);

    @TestConfiguration
    static class StubAi {
        @Bean
        @Primary
        DocumentReader stubReader() {
            return new DocumentReader() {
                public String name() { return "AI-STUB"; }
                public boolean extractsFields() { return true; }
                public DocumentReading read(byte[] b, String ct, String file, String declared) {
                    Map<String, FieldValue> f = new LinkedHashMap<>();
                    if (file.startsWith("pan")) {
                        f.put("pan", new FieldValue("DEEPM4321D", 0.95, "ocr"));
                        f.put("name", new FieldValue(file.contains("review") ? "DEEPA MENN" : "DEEPA MENON", file.contains("review") ? 0.55 : 0.96, "ocr"));
                        f.put("dob", new FieldValue("1990-04-15", 0.95, "ocr"));
                        boolean review = file.contains("review");
                        return new DocumentReading(review ? "NEEDS_REVIEW" : "OK", "PAN", 0.99, f, List.of(Map.of("check", "PAN format", "pass", true)),
                                Map.of("issues", List.of()), Map.of("score", 0, "signals", List.of()),
                                review ? List.of("Low reading confidence (75%)") : List.of(), review ? 0.75 : 0.95, "tesseract", false, null);
                    }
                    if (file.startsWith("aadhaar")) {
                        f.put("aadhaarMasked", new FieldValue("XXXX XXXX 0369", 0.96, "derived"));
                        f.put("aadhaarLast4", new FieldValue("0369", 0.96, "derived"));
                        f.put("name", new FieldValue("Deepa Menon", 0.96, "ocr"));
                        f.put("dob", new FieldValue("1990-04-15", 0.95, "ocr"));
                        f.put("pincode", new FieldValue("411038", 0.96, "ocr"));
                        return new DocumentReading("OK", "AADHAAR", 0.99, f, List.of(), Map.of("issues", List.of()), Map.of("score", 0),
                                List.of(), 0.95, "tesseract", true, MASKED_PNG);
                    }
                    if (file.startsWith("statement")) return analyzeStatement(b, ct, file, null, null).reading();
                    f.put("employeeName", new FieldValue("Deepa Menon", 0.96, "ocr"));
                    f.put("netPay", new FieldValue("80000", 0.96, "ocr"));
                    return new DocumentReading("OK", "SALARY_SLIP", 0.99, f, List.of(), Map.of("issues", List.of()), Map.of("score", 0),
                            List.of(), 0.96, "tesseract", false, null);
                }

                public boolean readsStatements() { return true; }

                public byte[] unlockPdf(byte[] pdf, String password) {
                    if (!"PW1234".equals(password)) throw com.rhythm.los.common.ApiException.unprocessable("WRONG_PASSWORD", "The PDF password is not correct");
                    return UNLOCKED_PDF;
                }

                public StatementReading analyzeStatement(byte[] b, String ct, String file, String holder, Double income) {
                    // what the AI service returns for a 6-month salaried statement with betting transactions
                    String json = """
                        {"status":"OK","documentType":"BANK_STATEMENT","engine":"pdf-text","bank":"SAHYADRI CO-OPERATIVE BANK LTD",
                         "accountHolder":"DEEPA MENON","accountNumberMasked":"XXXX4821","ifsc":"SAHY0000123",
                         "periodFrom":"2026-04-01","periodTo":"2026-09-30","transactionCount":2,
                         "transactions":[{"date":"2026-04-01","narration":"NEFT SALARY ACME","debit":0,"credit":80000,"balance":92000,"category":"SALARY","flags":[]},
                                         {"date":"2026-04-03","narration":"UPI DREAM11","debit":2000,"credit":0,"balance":90000,"category":"GAMBLING","flags":["Betting / gaming"]}],
                         "monthly":[%s],
                         "summary":{"months":6,"avgBalance":30000,"bounces":0,"detectedEmi":4500,"gamblingCount":3,"salary":{"amount":80000}},
                         "reconciliation":{"checked":120,"passed":120,"rate":1.0,"breaks":[]},
                         "tamper":{"score":0,"signals":[]},"notes":[],"reviewReasons":[],"overallConfidence":1.0,"headerFound":true}
                        """.formatted(String.join(",", java.util.Collections.nCopies(6, "{\"month\":\"2026-04\",\"inflow\":80000,\"outflow\":52000,\"avgBalance\":30000}")));
                    return com.rhythm.los.document.HttpDocumentReader.parseStatement(json);
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

    record Res(int status, JsonNode body, byte[] raw) {}

    Res call(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        b.header("Authorization", "Bearer " + token);
        if (body != null) b.contentType(MediaType.APPLICATION_JSON).content(Json.write(body));
        MvcResult r = mvc.perform(b).andReturn();
        byte[] raw = r.getResponse().getContentAsByteArray();
        String s = r.getResponse().getContentAsString();
        JsonNode n = null;
        try { n = s.isEmpty() ? null : Json.MAPPER.readTree(s); } catch (Exception ignored) { }
        return new Res(r.getResponse().getStatus(), n, raw);
    }

    Res ok(MockHttpServletRequestBuilder b, String token, Object body) throws Exception {
        Res r = call(b, token, body);
        assertThat(r.status()).as(r.body() == null ? "" : r.body().toString()).isEqualTo(200);
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

    void upload(String token, long id, String type, String file) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                .file(new MockMultipartFile("file", file, "image/png", ("PNG " + file).getBytes()))
                .param("type", type).header("Authorization", "Bearer " + token)).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
    }

    JsonNode states(long id, String token) throws Exception {
        return ok(get("/api/applications/" + id), token, null).body().get("states");
    }

    JsonNode doc(long id, String token, String type) throws Exception {
        for (JsonNode d : ok(get("/api/applications/" + id + "/documents"), token, null).body())
            if (type.equals(d.get("docType").asText())) return d;
        throw new AssertionError("no " + type);
    }

    @Test
    void documentsAreReadReviewedMaskedAndDriveKyc() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, "Deepa Menon", "DEEPM4321D", "9811100001");
        upload(sales, id, "PAN", "pan-review.png");
        upload(sales, id, "AADHAAR", "aadhaar.png");
        upload(sales, id, "SALARY_SLIP", "salary.png");

        JsonNode steps = ok(post("/api/applications/" + id + "/process"), ops, null).body().get("steps");
        JsonNode st = states(id, ops);
        assertThat(st.get("DOCS").asText()).isEqualTo("IN_REVIEW");
        assertThat(st.get("KYC").asText()).isEqualTo("NOT_STARTED");
        assertThat(steps.toString()).contains("verify the documents first");
        assertThat(ok(get("/api/my-work"), ops, null).body().toString()).contains("Document review");

        // Aadhaar: only the masked image is kept
        JsonNode aad = doc(id, ops, "AADHAAR");
        assertThat(aad.get("masked").asBoolean()).isTrue();
        assertThat(aad.get("fileName").asText()).endsWith("-masked.png");
        Res content = call(get("/api/applications/" + id + "/documents/" + aad.get("id").asLong() + "/content"), ops, null);
        assertThat(content.raw()).isEqualTo(MASKED_PNG);
        Res unmasked = call(post("/api/applications/" + id + "/documents/" + aad.get("id").asLong() + "/review"), ops,
                Map.of("action", "APPROVE", "fields", Map.of("aadhaarLast4", "234567890369"), "note", "Trying to store the full number"));
        assertThat(unmasked.status()).isEqualTo(422);

        // the reviewer corrects the name the AI misread and approves
        JsonNode pan = doc(id, ops, "PAN");
        assertThat(pan.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(call(post("/api/applications/" + id + "/documents/" + pan.get("id").asLong() + "/review"), sales,
                Map.of("action", "APPROVE", "note", "Checked against the card")).status()).isEqualTo(403);
        ok(post("/api/applications/" + id + "/documents/" + pan.get("id").asLong() + "/review"), ops,
                Map.of("action", "APPROVE", "fields", Map.of("name", "DEEPA MENON"), "note", "Name corrected from the card image"));
        assertThat(states(id, ops).get("DOCS").asText()).isEqualTo("COMPLETE");
        assertThat(doc(id, ops, "PAN").get("extractedJson").asText()).contains("reviewer");

        ok(post("/api/applications/" + id + "/process"), ops, null);
        st = states(id, ops);
        assertThat(st.get("KYC").asText()).isEqualTo("VERIFIED");
        assertThat(st.get("DECISION").asText()).isNotEqualTo("PENDING");
        String checks = ok(get("/api/applications/" + id + "/kyc-checks"), ops, null).body().toString();
        assertThat(checks).contains("PAN on card matches application").contains("Name on PAN matches")
                .contains("PIN code on Aadhaar matches").contains("Employee name on salary slip matches").doesNotContain("\"FAIL\"");
    }

    @Test
    void documentsOfSomeoneElseFailKyc() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, "Rohit Sharma", "ROHPS7788R", "9811100002");
        upload(sales, id, "PAN", "pan.png");
        upload(sales, id, "AADHAAR", "aadhaar.png");
        upload(sales, id, "SALARY_SLIP", "salary.png");
        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode st = states(id, ops);
        assertThat(st.get("KYC").asText()).isEqualTo("FAILED");
        String checks = ok(get("/api/applications/" + id + "/kyc-checks"), ops, null).body().toString();
        assertThat(checks).contains("\"FAIL\"");
    }

    Res uploadPdf(String token, long id, String file, byte[] bytes, String password) throws Exception {
        var b = multipart("/api/applications/" + id + "/documents").file(new MockMultipartFile("file", file, "application/pdf", bytes)).param("type", "BANK_STATEMENT");
        if (password != null) b.param("password", password);
        return call(b, token, null);
    }

    @Test
    void lockedBankStatementIsUnlockedAnalysedAndDrivesTheDecision() throws Exception {
        String sales = login("sales1"), ops = login("ops1");
        long id = create(sales, "Deepa Menon", "DEEPM4321D", "9811100003");
        upload(sales, id, "PAN", "pan.png");
        upload(sales, id, "AADHAAR", "aadhaar.png");
        upload(sales, id, "SALARY_SLIP", "salary.png");

        Res noPw = uploadPdf(sales, id, "statement.pdf", LOCKED_PDF, null);
        assertThat(noPw.status()).isEqualTo(422);
        assertThat(noPw.body().toString()).contains("PASSWORD_REQUIRED");
        Res wrong = uploadPdf(sales, id, "statement.pdf", LOCKED_PDF, "nope");
        assertThat(wrong.status()).isEqualTo(422);
        assertThat(wrong.body().toString()).contains("WRONG_PASSWORD");
        Res good = uploadPdf(sales, id, "statement.pdf", LOCKED_PDF, "PW1234");
        assertThat(good.status()).as(String.valueOf(good.body())).isEqualTo(200);
        assertThat(good.body().get("remarks").asText()).contains("password was not stored");
        long docId = good.body().get("id").asLong();
        assertThat(call(get("/api/applications/" + id + "/documents/" + docId + "/content"), ops, null).raw()).isEqualTo(UNLOCKED_PDF);
        assertThat(ok(get("/api/audit?applicationId=" + id), login("comp1"), null).raw()).asString().doesNotContain("PW1234");

        ok(post("/api/applications/" + id + "/process"), ops, null);
        JsonNode st = doc(id, ops, "BANK_STATEMENT");
        assertThat(st.get("status").asText()).isEqualTo("VERIFIED");
        assertThat(st.get("hasAnalysis").asBoolean()).isTrue();
        assertThat(st.get("extractedJson").asText()).contains("XXXX4821").contains("DEEPA MENON");
        JsonNode analysis = ok(get("/api/applications/" + id + "/documents/" + docId + "/analysis"), ops, null).body();
        assertThat(analysis.get("monthly").size()).isEqualTo(6);
        assertThat(analysis.has("unlockedPdf")).isFalse();

        assertThat(ok(get("/api/applications/" + id + "/snapshots"), ops, null).body().toString()).contains("STATEMENT-AI");
        assertThat(ok(get("/api/applications/" + id + "/kyc-checks"), ops, null).body().toString()).contains("Account holder on bank statement matches");
        String decision = ok(get("/api/applications/" + id + "/decision"), ops, null).body().toString();
        assertThat(decision).contains("R14").contains("RC-020");
        assertThat(states(id, ops).get("DECISION").asText()).isEqualTo("REFER");
    }
}
