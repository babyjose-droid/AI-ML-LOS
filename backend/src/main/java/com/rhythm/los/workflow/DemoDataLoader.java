package com.rhythm.los.workflow;

import com.rhythm.los.application.ApplicationRequest;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.Domain;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.disbursement.DisbursementService;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.org.Role;
import com.rhythm.los.sanction.SanctionService;
import com.rhythm.los.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/** Creates six demo applications at different stages on first start, so every screen has data. */
@Component
public class DemoDataLoader {
    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);

    private final boolean enabled;
    private final LoanApplicationRepository repo;
    private final ApplicationService apps;
    private final DocumentService docs;
    private final ProcessService process;
    private final SanctionService sanctions;
    private final DisbursementService disbursements;
    private final TransactionTemplate tx;

    static final CurrentUser SALES = new CurrentUser("sales1", Role.SALES, "Sameer Kulkarni", 4L);
    static final CurrentUser OPS = new CurrentUser("ops1", Role.OPERATIONS, "Neha Joshi", 4L);
    static final CurrentUser CM = new CurrentUser("cm1", Role.CREDIT_MANAGER, "Anita Rao", 3L);

    public DemoDataLoader(@Value("${rhythm.demo-data}") boolean enabled, LoanApplicationRepository repo, ApplicationService apps,
                          DocumentService docs, ProcessService process, SanctionService sanctions,
                          DisbursementService disbursements, TransactionTemplate tx) {
        this.enabled = enabled;
        this.repo = repo;
        this.apps = apps;
        this.docs = docs;
        this.process = process;
        this.sanctions = sanctions;
        this.disbursements = disbursements;
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        if (!enabled || repo.count() > 0) return;
        log.info("Loading demo applications");
        step("Ramesh", () -> {
            LoanApplication a = create("BL-UNS", "Ramesh Patil", "RAMPP1001R", "9822001001", "1985-06-15", "422013", "Nashik", "Patil Kirana Stores", 8, 62000, 21000, 300000, 24, "Stock purchase for festival season");
            docs(a, "ramesh", "PAN", "AADHAAR", "UDYAM");
            process.process(a.getId(), OPS);
        });
        step("Priya", () -> {
            LoanApplication a = create("PL", "Priya Nair", "PRIPN1002P", "9822001002", "1992-03-02", "682020", "Kochi", null, 6, 72000, 30000, 500000, 24, "Home renovation");
            docs(a, "priya", "PAN", "AADHAAR", "SALARY_SLIP");
            process.process(a.getId(), OPS);
            sanctions.approve(a.getId(), null, null, CM);
            sanctions.acceptKfs(a.getId(), "123456", SALES);
            disbursements.disburse(a.getId(), OPS);
        });
        step("Lakshmi", () -> {
            LoanApplication a = create("JLG", "Lakshmi Devi", "LAKPD1003L", "9822001003", "1990-01-20", "625020", "Madurai", "Dairy (2 cows)", 5, 22000, 9000, 50000, 18, "Buy a third milch cow");
            docs(a, "lakshmi", "AADHAAR");
            process.process(a.getId(), OPS);
        });
        step("Suresh", () -> {
            LoanApplication a = create("BL-UNS", "Suresh Yadav", "SURPY1004S", "9822001004", "1981-09-11", "208023", "Kanpur", "Yadav Transport", 4, 95000, 38000, 600000, 36, "Down payment on a used truck");
            docs(a, "suresh", "PAN", "AADHAAR", "UDYAM");
            process.process(a.getId(), OPS);
            tx.executeWithoutResult(s -> {
                LoanApplication x = apps.get(a.getId());
                apps.transition(x, Domain.FIELD, "DONE", "field.visit_completed", OPS, "Visited depot: 3 trucks on site, books seen, owner present");
            });
            process.process(a.getId(), OPS);
        });
        step("Anil", () -> {
            LoanApplication a = create("PL", "Anil Kumar", "ANIPK1005A", "9822001005", "1997-07-10", "411005", "Pune", null, 1, 95000, 18000, 400000, 36, "Personal use");
            docs(a, "anil", "PAN", "AADHAAR");
            if (docs.aiEnabled()) specimen(a, "SALARY_SLIP", "anil-salary-slip.jpg", "image/jpeg");
            else uploadNamed(a, "SALARY_SLIP", "salary-slip-tamper.pdf");
            process.process(a.getId(), OPS);
        });
        step("Meera", () -> {
            LoanApplication a = create("BL-UNS", "Meera Shah", "MEEPS9201M", "9822001006", "1988-12-05", "422001", "Nashik", "Shah Tailoring", 6, 48000, 18000, 150000, 18, "Two new sewing machines");
            docs(a, "meera", "PAN", "AADHAAR", "UDYAM");
            process.process(a.getId(), OPS);
        });
        create("BL-UNS", "Sunil Wagh", "SUNPW1234W", "9822001007", "1993-02-14", "411001", "Pune", "Wagh Hardware", 3, 55000, 20000, 200000, 24, "Working capital");
        log.info("Demo applications loaded");
    }

    private void step(String name, Runnable r) {
        try { r.run(); } catch (Exception e) { log.warn("Demo case {} stopped early: {}", name, e.getMessage()); }
    }

    private LoanApplication create(String product, String name, String pan, String mobile, String dob, String pin, String city, String business,
                                   double vintage, double income, double essentials, double amount, int tenure, String purpose) {
        ApplicationRequest r = new ApplicationRequest(product, name, pan, mobile, null,
                LocalDate.parse(dob), null, "12 Main Road, " + city, city, pin, business,
                BigDecimal.valueOf(vintage), BigDecimal.valueOf(income), BigDecimal.valueOf(essentials), BigDecimal.valueOf(amount),
                tenure, purpose, "50100" + mobile.substring(4), "HDFC0001234", true, true, true);
        return apps.create(r, SALES);
    }

    /** With the AI service on, uploads the SPECIMEN cards for the persona; otherwise small placeholder PDFs. */
    private void docs(LoanApplication a, String persona, String... types) {
        for (String t : types) {
            String file = persona + "-" + switch (t) { case "SALARY_SLIP" -> "salary"; default -> t.toLowerCase(); } + ".png";
            if (docs.aiEnabled()) specimen(a, t, file, "image/png");
            else uploadNamed(a, t, t.toLowerCase() + ".pdf");
        }
    }

    private void specimen(LoanApplication a, String type, String file, String contentType) {
        try (InputStream in = getClass().getResourceAsStream("/demo-docs/" + file)) {
            if (in == null) throw new IllegalStateException("Missing demo document " + file);
            docs.upload(a.getId(), type, new BytesFile(file, in.readAllBytes(), contentType), SALES);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void uploadNamed(LoanApplication a, String type, String fileName) {
        docs.upload(a.getId(), type, new BytesFile(fileName, ("%PDF-1.4\n% demo " + type + " for " + a.getApplicantName() + "\n%%EOF").getBytes(StandardCharsets.UTF_8), "application/pdf"), SALES);
    }

    /** Minimal in-memory MultipartFile for seeding. */
    record BytesFile(String name, byte[] bytes, String type) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return type; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) { throw new UnsupportedOperationException(); }
    }
}
