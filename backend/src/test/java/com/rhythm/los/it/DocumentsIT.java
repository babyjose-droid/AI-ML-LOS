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

/** Loan document templates: library and approval, product documents, the application pack, signatures and the disbursement gate. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "rhythm.demo-data=false",
        "rhythm.integration.backoff-ms=0",
        "rhythm.storage.path=target/test-documents-docgen"
})
class DocumentsIT {

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

    long sanctionedPl(String sales, String ops, String cm, String name, String pan, String mobile) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productCode", "PL"); m.put("applicantName", name); m.put("pan", pan); m.put("mobile", mobile);
        m.put("dob", LocalDate.now().minusYears(34).toString()); m.put("city", "Nashik"); m.put("address", "7 Book Road");
        m.put("pincode", "422001"); m.put("businessVintageYears", 6); m.put("declaredMonthlyIncome", 72000);
        m.put("essentialExpenses", 30000); m.put("loanAmount", 500000); m.put("tenureMonths", 24); m.put("purpose", "Home repairs");
        m.put("bankAccountNo", "5010099887766"); m.put("bankIfsc", "HDFC0001234"); m.put("consentBureau", true); m.put("consentAa", true); m.put("consentKyc", true);
        long id = ok(post("/api/applications"), sales, m).body().get("id").asLong();
        for (String t : List.of("PAN", "AADHAAR", "SALARY_SLIP")) {
            MvcResult r = mvc.perform(multipart("/api/applications/" + id + "/documents")
                    .file(new MockMultipartFile("file", t.toLowerCase() + ".pdf", "application/pdf", "%PDF-1.4 test".getBytes()))
                    .param("type", t).header("Authorization", "Bearer " + sales)).andReturn();
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
        }
        ok(post("/api/applications/" + id + "/process"), ops, null);
        return id;
    }

    JsonNode doc(JsonNode pack, String code) {
        for (JsonNode d : pack.get("documents")) if (d.get("templateCode").asText().equals(code)) return d;
        return null;
    }

    @Test
    void templatesAreDraftedApprovedAndUsedByProducts() throws Exception {
        String legal = login("legal1"), comp = login("comp1"), sales = login("sales1"), admin = login("admin");
        JsonNode lib = ok(get("/api/doc-templates"), comp, null).body();
        assertThat(lib.get("templates").size()).isEqualTo(20);
        assertThat(lib.toString()).contains("LOAN_AGREEMENT").contains("CL_INTEREST").contains("KFS_STANDARD").contains("applicant.name");
        assertThat(call(get("/api/doc-templates"), sales, null).status()).isEqualTo(403);
        long agreementV1 = -1;
        for (JsonNode t : lib.get("templates")) if (t.get("code").asText().equals("LOAN_AGREEMENT")) agreementV1 = t.get("versions").get(0).get("id").asLong();

        // a new version: a field that does not exist is caught before it can go for approval
        long v2 = ok(post("/api/doc-templates"), legal, Map.of("fromId", agreementV1, "notes", "Add the branch and an insurance line")).body().get("id").asLong();
        String body = ok(get("/api/doc-templates/" + v2), legal, null).body().get("template").get("body").asText();
        ok(put("/api/doc-templates/" + v2), legal, Map.of("body", body + "<p>Branch: {{branch.nam}}</p>"));
        assertThat(ok(get("/api/doc-templates/" + v2), legal, null).body().get("problems").toString()).contains("Unknown field {{branch.nam}}");
        assertThat(call(post("/api/doc-templates/" + v2 + "/submit"), legal, null).body().get("code").asText()).isEqualTo("TEMPLATE_INVALID");
        ok(put("/api/doc-templates/" + v2), legal, Map.of("body", body + "<p>Branch: {{branch.name}}. {{#if loan.insurance}}Insurance {{loan.insurance | rupees}}.{{/if}}</p>"));
        JsonNode prev = ok(post("/api/doc-templates/preview"), legal, Map.of("templateId", v2)).body();
        assertThat(prev.get("html").asText()).contains("Loan agreement").contains("Rupees Six Lakh Only");
        assertThat(ok(post("/api/doc-templates/" + v2 + "/submit"), legal, null).body().get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(call(post("/api/doc-templates/" + v2 + "/decision"), legal, Map.of("approve", true, "note", "Approving my own draft")).status()).isEqualTo(403);
        ok(post("/api/doc-templates/" + v2 + "/decision"), comp, Map.of("approve", true, "note", "Legal reviewed the added clause"));
        assertThat(ok(get("/api/doc-templates/" + agreementV1), comp, null).body().get("template").get("status").asText()).isEqualTo("RETIRED");

        // a KFS cannot drop RBI's standard table; a Hindi version of a letter is its own template language
        long kfs = -1;
        for (JsonNode t : lib.get("templates")) if (t.get("code").asText().equals("KFS")) kfs = t.get("versions").get(0).get("id").asLong();
        long kfs2 = ok(post("/api/doc-templates"), legal, Map.of("fromId", kfs, "body", "<h1>KFS</h1><p>{{loan.amount | rupees}}</p>", "notes", "Simplify the KFS layout")).body().get("id").asLong();
        assertThat(call(post("/api/doc-templates/" + kfs2 + "/submit"), legal, null).body().toString()).contains("KFS_STANDARD");
        assertThat(call(delete("/api/doc-templates/" + kfs2), legal, null).status()).isEqualTo(204);
        long hi = ok(post("/api/doc-templates"), legal, Map.of("code", "WELCOME_LETTER", "docType", "WELCOME", "language", "hi", "name", "स्वागत पत्र",
                "body", "<h1>स्वागत है, {{applicant.name}}</h1><p>आपका ऋण {{loan.amount | rupees}}</p>", "notes", "Hindi welcome letter for UP branches")).body().get("id").asLong();
        ok(post("/api/doc-templates/" + hi + "/submit"), legal, null);
        ok(post("/api/doc-templates/" + hi + "/decision"), comp, Map.of("approve", true, "note", "Translation checked by the branch"));
        // a new clause, then a product document that is not a clause
        long cl = ok(post("/api/doc-templates"), legal, Map.of("code", "CL_INSURANCE", "docType", "CLAUSE", "name", "Clause: insurance",
                "body", "<h3>Insurance</h3><p>The borrower may choose any insurer.</p>", "notes", "Insurance is optional (RBI)")).body().get("id").asLong();
        assertThat(call(post("/api/doc-templates"), legal, Map.of("code", "INSURANCE", "docType", "CLAUSE", "body", "x")).body().get("code").asText()).isEqualTo("BAD_CODE");
        ok(post("/api/doc-templates/" + cl + "/submit"), legal, null);
        ok(post("/api/doc-templates/" + cl + "/decision"), comp, Map.of("approve", true, "note", "Clause approved by compliance"));

        // product documents: what PL uses; admin turns on the disbursement gate
        JsonNode pd = ok(get("/api/products/PL/documents"), sales, null).body();
        assertThat(pd.get("documents").toString()).contains("LOAN_AGREEMENT").contains("DPN").contains("KFS").doesNotContain("MORTGAGE_MOD");
        assertThat(ok(get("/api/products/LAP/documents"), sales, null).body().toString()).contains("MORTGAGE_MOD");
        assertThat(call(put("/api/products/PL/documents"), sales, Map.of("gate", true, "documents", List.of(), "note", "Turn on the gate")).status()).isEqualTo(403);
        assertThat(call(put("/api/products/PL/documents"), admin, Map.of("gate", true, "documents", List.of(Map.of("templateCode", "CL_INTEREST", "stage", "SANCTION")), "note", "Trying a clause"))
                .body().get("code").asText()).isEqualTo("CLAUSE");
    }

    @Test
    void theDocumentPackHoldsDisbursementUntilSigned() throws Exception {
        String sales = login("sales1"), ops = login("ops1"), cm = login("cm1"), admin = login("admin");
        // PL: the starter documents, signed before disbursement
        List<Map<String, Object>> lines = new ArrayList<>();
        for (JsonNode d : ok(get("/api/products/PL/documents"), admin, null).body().get("documents"))
            lines.add(Map.of("templateCode", d.get("templateCode").asText(), "stage", d.get("stage").asText(),
                    "signers", Json.MAPPER.convertValue(d.get("signers"), List.class), "condition", d.get("condition").asText(), "required", d.get("required").asBoolean()));
        ok(put("/api/products/PL/documents"), admin, Map.of("gate", true, "documents", lines, "note", "Personal loans: documents signed before disbursement"));

        long id = sanctionedPl(sales, ops, cm, "Pack Person", "PACPP1002P", "9866000071");
        JsonNode pack = ok(get("/api/applications/" + id + "/documents-pack"), ops, null).body();
        assertThat(doc(pack, "LOAN_AGREEMENT").get("notYet").asText()).isEqualTo("after sanction");
        // the application form can be made now; sanction documents only after sanction
        JsonNode form = ok(post("/api/applications/" + id + "/documents-pack/generate"), ops, Map.of("stage", "APPLICATION")).body().get(0);
        assertThat(form.get("templateCode").asText()).isEqualTo("APPLICATION_FORM");
        assertThat(call(post("/api/applications/" + id + "/documents-pack/generate"), ops, Map.of("stage", "PRE_DISBURSE")).body().get("code").asText()).isEqualTo("NOT_YET");
        byte[] pdf = mvc.perform(get("/api/app-documents/" + form.get("id").asLong() + "/pdf").header("Authorization", "Bearer " + ops)).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(ok(get("/api/app-documents/" + form.get("id").asLong() + "/html"), ops, null).body().get("html").asText()).contains("Pack Person").contains("Home repairs");

        ok(post("/api/applications/" + id + "/sanction"), cm, Map.of());
        ok(post("/api/applications/" + id + "/kfs/accept"), sales, Map.of("otp", "123456"));
        Res held = call(post("/api/applications/" + id + "/disburse"), ops, null);
        assertThat(held.status()).isEqualTo(409);
        assertThat(held.body().toString()).contains("Loan agreement has not been made").contains("waiting for Pack Person's signature");

        for (String st : List.of("SANCTION", "PRE_DISBURSE")) ok(post("/api/applications/" + id + "/documents-pack/generate"), ops, Map.of("stage", st));
        pack = ok(get("/api/applications/" + id + "/documents-pack"), ops, null).body();
        JsonNode agreement = doc(pack, "LOAN_AGREEMENT").get("document");
        assertThat(agreement.get("templateVersion").asInt()).isPositive();
        assertThat(agreement.has("html")).isFalse();
        // the sanction letter shows the sanctioned terms
        assertThat(ok(get("/api/app-documents/" + doc(pack, "SANCTION_LETTER").get("document").get("id").asLong() + "/html"), ops, null).body().get("html").asText())
                .contains("Pack Person").contains("Rupees").contains("Lakh");
        // sign everything: wrong OTP refused, the signer list comes from the product
        long aid = agreement.get("id").asLong();
        assertThat(call(post("/api/app-documents/" + aid + "/sign"), sales, Map.of("role", "BORROWER", "otp", "000000")).body().get("code").asText()).isEqualTo("BAD_OTP");
        assertThat(call(post("/api/app-documents/" + aid + "/sign"), sales, Map.of("role", "GUARANTOR", "partyId", 999, "otp", "123456")).body().get("code").asText()).isEqualTo("NOT_A_SIGNER");
        for (JsonNode d : pack.get("documents")) {
            if (!d.has("document")) continue;
            for (JsonNode s : d.get("signers")) ok(post("/api/app-documents/" + d.get("document").get("id").asLong() + "/sign"), sales, Map.of("role", s.get("role").asText(), "otp", "123456"));
        }
        assertThat(call(post("/api/app-documents/" + aid + "/sign"), sales, Map.of("role", "BORROWER", "otp", "123456")).body().get("code").asText()).isEqualTo("ALREADY_SIGNED");
        pack = ok(get("/api/applications/" + id + "/documents-pack"), ops, null).body();
        assertThat(pack.get("blockers").size()).isZero();
        assertThat(doc(pack, "LOAN_AGREEMENT").get("complete").asBoolean()).isTrue();
        assertThat(ok(post("/api/applications/" + id + "/disburse"), ops, null).body().get("status").asText()).isEqualTo("SUCCESS");
        // the welcome letter is made after disbursement and carries the loan number
        JsonNode welcome = ok(post("/api/applications/" + id + "/documents-pack/generate"), ops, Map.of("stage", "POST_DISBURSE")).body().get(0);
        assertThat(ok(get("/api/app-documents/" + welcome.get("id").asLong() + "/html"), ops, null).body().get("html").asText()).contains("LN-");
        // signed documents of disbursed loans are not reported as out of date
        assertThat(doc(ok(get("/api/applications/" + id + "/documents-pack"), ops, null).body(), "LOAN_AGREEMENT").get("outdated").asBoolean()).isFalse();
        assertThat(ok(get("/api/audit?applicationId=" + id), login("comp1"), null).body().toString()).contains("documents.generated").contains("documents.signed");
        ok(put("/api/products/PL/documents"), admin, Map.of("gate", false, "documents", lines, "note", "Back to no gate for other tests"));
    }
}
