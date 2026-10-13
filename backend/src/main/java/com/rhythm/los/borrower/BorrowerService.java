package com.rhythm.los.borrower;

import com.rhythm.los.application.ApplicationRequest;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.document.AppDocument;
import com.rhythm.los.document.DocumentService;
import com.rhythm.los.org.Role;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.SanctionService;
import com.rhythm.los.security.CurrentUser;
import com.rhythm.los.security.JwtService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Borrower self-service: sign in with mobile OTP, apply, upload documents, follow progress and accept the Key Fact
 * Statement. A borrower only ever sees applications made with their own mobile number, and only borrower-safe fields.
 */
@Service
public class BorrowerService {
    private static final SecureRandom RNG = new SecureRandom();
    static final int MAX_OTPS_PER_HOUR = 5;
    static final int MAX_ATTEMPTS = 5;

    private final BorrowerOtpRepository otps;
    private final LoanApplicationRepository appRepo;
    private final ApplicationService apps;
    private final DocumentService documents;
    private final SanctionService sanctions;
    private final ProductRepository products;
    private final JwtService jwt;
    private final AuditService audit;
    private final String fixedOtp;
    private final long sessionMinutes;

    public BorrowerService(BorrowerOtpRepository otps, LoanApplicationRepository appRepo, ApplicationService apps, DocumentService documents,
                           SanctionService sanctions, ProductRepository products, JwtService jwt, AuditService audit,
                           @Value("${rhythm.borrower.demo-otp:123456}") String fixedOtp,
                           @Value("${rhythm.borrower.session-minutes:60}") long sessionMinutes) {
        this.otps = otps;
        this.appRepo = appRepo;
        this.apps = apps;
        this.documents = documents;
        this.sanctions = sanctions;
        this.products = products;
        this.jwt = jwt;
        this.audit = audit;
        this.fixedOtp = fixedOtp;
        this.sessionMinutes = sessionMinutes;
    }

    // ---------------------------------------------------------------- sign-in

    /** Creates an OTP. Until the SMS integration is live, the demo OTP is used (shown on the sign-in page). */
    @Transactional
    public Map<String, Object> requestOtp(String mobile) {
        String m = mobile(mobile);
        if (otps.countByMobileAndCreatedAtAfter(m, Instant.now().minus(Duration.ofHours(1))) >= MAX_OTPS_PER_HOUR)
            throw new ApiException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_OTPS", "Too many OTP requests. Try again in an hour.");
        String code = fixedOtp == null || fixedOtp.isBlank() ? String.format("%06d", RNG.nextInt(1_000_000)) : fixedOtp;
        otps.save(new BorrowerOtp(m, hash(m, code), Instant.now().plus(Duration.ofMinutes(5))));
        // SMS delivery is added with the integrations phase
        return Map.of("sent", true, "expiresInSeconds", 300, "demo", fixedOtp != null && !fixedOtp.isBlank());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> verifyOtp(String mobile, String code) {
        String m = mobile(mobile);
        BorrowerOtp o = otps.findFirstByMobileOrderByIdDesc(m).orElseThrow(() -> ApiException.unprocessable("NO_OTP", "Request an OTP first"));
        if (o.isUsed() || Instant.now().isAfter(o.getExpiresAt())) throw ApiException.unprocessable("OTP_EXPIRED", "This OTP has expired. Request a new one.");
        if (o.getAttempts() >= MAX_ATTEMPTS) throw ApiException.unprocessable("TOO_MANY_ATTEMPTS", "Too many wrong attempts. Request a new OTP.");
        o.setAttempts(o.getAttempts() + 1);
        if (code == null || !hash(m, code.trim()).equals(o.getCodeHash())) {
            otps.save(o);
            throw ApiException.unprocessable("BAD_OTP", "The OTP is not correct");
        }
        o.setUsed(true);
        otps.save(o);
        audit.record("borrower:" + m, "BORROWER", "borrower.signed_in", "BORROWER", m.substring(0, 2) + "XXXXXX" + m.substring(8), null, "OTP verified");
        return Map.of("token", jwt.issueBorrower(m, sessionMinutes), "mobile", m, "expiresInMinutes", sessionMinutes);
    }

    // ---------------------------------------------------------------- applications

    public List<Map<String, Object>> products() {
        return products.findAll().stream().filter(p -> "LIVE".equals(p.getStatus())).map(p -> Map.<String, Object>of(
                "code", p.getCode(), "name", p.getName(), "minAmount", p.getMinAmount(), "maxAmount", p.getMaxAmount(),
                "minTenure", p.getMinTenure(), "maxTenure", p.getMaxTenure(), "documents", p.requiredDocList())).toList();
    }

    public List<Map<String, Object>> mine(CurrentUser by) {
        return appRepo.findByMobileOrderByIdDesc(mobileOf(by)).stream().map(a -> Map.<String, Object>of(
                "id", a.getId(), "appNo", a.getAppNo(), "product", a.getProductCode(), "amount", a.getLoanAmount(),
                "stage", stage(a).get("label"), "createdAt", a.getCreatedAt())).toList();
    }

    @Transactional
    public Map<String, Object> apply(ApplicationRequest r, CurrentUser by) {
        String m = mobileOf(by);
        ApplicationRequest req = new ApplicationRequest(r.productCode(), r.applicantName(), r.pan(), m, r.email(), r.dob(), r.gender(), r.address(),
                r.city(), r.pincode(), r.businessName(), r.businessVintageYears(), r.declaredMonthlyIncome(), r.essentialExpenses(), r.loanAmount(),
                r.tenureMonths(), r.purpose(), r.bankAccountNo(), r.bankIfsc(), r.consentBureau(), r.consentAa(), r.consentKyc(), null);
        if (!r.consentBureau() || !r.consentAa() || !r.consentKyc())
            throw ApiException.unprocessable("CONSENT_MISSING", "Please give all three consents to apply");
        LoanApplication a = apps.create(req, by);
        a.setChannel("BORROWER_PORTAL");
        appRepo.save(a);
        apps.submit(a.getId(), by);
        return view(a.getId(), by);
    }

    public LoanApplication owned(Long id, CurrentUser by) {
        LoanApplication a = appRepo.findById(id).orElseThrow(() -> ApiException.notFound("Application"));
        if (!mobileOf(by).equals(a.getMobile())) throw ApiException.notFound("Application"); // never reveal that it exists
        return a;
    }

    public Map<String, Object> view(Long id, CurrentUser by) {
        LoanApplication a = owned(id, by);
        Product p = apps.product(a);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("appNo", a.getAppNo());
        m.put("product", p.getName());
        m.put("productCode", p.getCode());
        m.put("amount", a.getLoanAmount());
        m.put("tenureMonths", a.getTenureMonths());
        m.put("applicantName", a.getApplicantName());
        m.put("createdAt", a.getCreatedAt());
        m.put("stage", stage(a));
        m.put("missingDocuments", ApplicationService.isOpen(a) ? documents.missing(a) : List.of());
        m.put("requiredDocuments", p.requiredDocList());
        m.put("canUpload", Set.of("DRAFT", "SUBMITTED", "UNDERWRITING").contains(a.getAppState()) && !Set.of("SANCTIONED", "KFS_ACCEPTED").contains(a.getSanctionState()));
        List<Map<String, Object>> docs = new ArrayList<>();
        for (AppDocument d : documents.list(a.getId())) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("docType", d.getDocType());
            x.put("fileName", d.getFileName());
            x.put("status", d.getStatus());
            x.put("message", borrowerMessage(d));
            x.put("uploadedAt", d.getUploadedAt());
            docs.add(x);
        }
        m.put("documents", docs);
        if (Set.of("SANCTIONED", "KFS_ACCEPTED").contains(a.getSanctionState())) m.put("kfs", sanctions.kfs(a.getId()));
        m.put("canAcceptKfs", "SANCTIONED".equals(a.getSanctionState()));
        return m;
    }

    public AppDocument upload(Long id, String type, MultipartFile file, String password, CurrentUser by) {
        LoanApplication a = owned(id, by);
        if (!ApplicationService.isOpen(a)) throw ApiException.conflict("CLOSED", "This application is closed");
        return documents.upload(a.getId(), type, file, password, by);
    }

    public Map<String, Object> acceptKfs(Long id, String otp, CurrentUser by) {
        owned(id, by);
        sanctions.acceptKfs(id, otp, by);
        return view(id, by);
    }

    /** What the borrower sees: plain steps, never internal scores, rules or reviewer notes. */
    static Map<String, Object> stage(LoanApplication a) {
        String app = a.getAppState();
        List<String> steps = List.of("Application received", "Documents checked", "Credit assessment", "Loan approved", "Terms accepted", "Money sent");
        int at;
        String label, note;
        if ("REJECTED".equals(app)) {
            return Map.of("index", -1, "label", "Not approved", "steps", steps,
                    "note", "We could not approve this application. You will receive a letter with the main reasons, as required by RBI's fair practices code.");
        }
        if ("WITHDRAWN".equals(app)) return Map.of("index", -1, "label", "Withdrawn", "steps", steps, "note", "This application was withdrawn.");
        if ("DISBURSED".equals(app)) { at = 5; label = "Money sent"; note = "The loan amount has been sent to your bank account."; }
        else if ("KFS_ACCEPTED".equals(a.getSanctionState())) { at = 4; label = "Terms accepted"; note = "Thank you. The money will be sent to your bank account shortly."; }
        else if ("SANCTIONED".equals(a.getSanctionState())) { at = 3; label = "Loan approved"; note = "Please read the Key Fact Statement below and accept it with an OTP."; }
        else if (!"PENDING".equals(a.getDecisionState()) || "COMPLETE".equals(a.getDocsState())) { at = 2; label = "Credit assessment"; note = "We are assessing your application. This usually takes less than a day."; }
        else if ("IN_REVIEW".equals(a.getDocsState())) { at = 1; label = "Documents checked"; note = "Our team is checking your documents."; }
        else { at = 0; label = "Application received"; note = "Please upload the documents listed below."; }
        return Map.of("index", at, "label", label, "steps", steps, "note", note);
    }

    static String borrowerMessage(AppDocument d) {
        return switch (d.getStatus()) {
            case "VERIFIED" -> "Verified";
            case "REJECTED" -> "We could not use this file. Please upload a clear, complete copy.";
            case "NEEDS_REVIEW" -> "Being checked by our team";
            default -> "Received";
        };
    }

    // ---------------------------------------------------------------- helpers

    static String mobile(String m) {
        String x = m == null ? "" : m.replaceAll("\\D", "");
        if (x.length() == 12 && x.startsWith("91")) x = x.substring(2);
        if (!x.matches("[6-9][0-9]{9}")) throw ApiException.unprocessable("BAD_MOBILE", "Enter a 10-digit Indian mobile number");
        return x;
    }

    static String mobileOf(CurrentUser by) {
        if (by.role() != Role.BORROWER || !by.username().startsWith("borrower:")) throw ApiException.forbidden("NOT_BORROWER", "Borrower sign-in required");
        return by.username().substring("borrower:".length());
    }

    static String hash(String mobile, String code) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest((mobile + ":" + code).getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
