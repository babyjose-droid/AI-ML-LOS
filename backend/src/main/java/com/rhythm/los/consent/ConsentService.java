package com.rhythm.los.consent;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Consent ledger (BR-04, FR-KYC-01/02): one record per data source with purpose, scope, validity, channel and language.
 * Every external pull checks it; a withdrawal stops further pulls and records its effect on the application.
 */
@Service
public class ConsentService {
    public record Terms(String purpose, String scope, Duration validity) {}

    /** Plain-language purpose and scope per source (shown to the borrower and stored with the consent). */
    public static final Map<String, Terms> TERMS = new LinkedHashMap<>();
    static {
        TERMS.put("KYC", new Terms("Verify your identity for this loan", "PAN, Aadhaar (masked), CKYC record and the documents you upload", Duration.ofDays(365)));
        TERMS.put("AA", new Terms("Assess your income and repayment capacity", "12 months of transactions of the bank account you choose, through an Account Aggregator", Duration.ofDays(365)));
        TERMS.put("BUREAU", new Terms("Check your credit history", "One credit report from a credit information company", Duration.ofDays(180)));
        TERMS.put("GST", new Terms("Confirm your business turnover", "GST returns of the last 12 months", Duration.ofDays(365)));
        TERMS.put("COMMUNICATION", new Terms("Send you updates about this application and loan", "SMS, WhatsApp and email to your registered number and address", null));
    }

    static final Map<String, String> EFFECT = Map.of(
            "KYC", "No further identity checks; KYC results already held stay on file",
            "AA", "No further bank data pulls (including monitoring refreshes); data already held is used only for this application",
            "BUREAU", "No further credit report pulls; the report already held is used only for this application",
            "GST", "No further GST pulls",
            "COMMUNICATION", "Only messages required by law are sent");

    private final ConsentRepository repo;
    private final AuditService audit;

    public ConsentService(ConsentRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public List<Consent> list(Long appId) { return repo.findByApplicationIdOrderByIdAsc(appId); }

    /** Records the consents given on the application form (once, at submission). */
    @Transactional
    public void recordFromApplication(LoanApplication a, CurrentUser by) {
        if (!repo.findByApplicationIdOrderByIdAsc(a.getId()).isEmpty()) return;
        String channel = "BORROWER_PORTAL".equals(a.getChannel()) ? "Borrower portal (OTP-verified mobile)" : "Branch / sales console";
        String lang = a.getPreferredLanguage() == null ? "en" : a.getPreferredLanguage();
        Instant now = Instant.now();
        List<String> sources = new ArrayList<>();
        if (a.isConsentKyc()) sources.add("KYC");
        if (a.isConsentAa()) sources.add("AA");
        if (a.isConsentBureau()) sources.add("BUREAU");
        // GST is asked of GST-registered businesses; until the form has its own box it is given with the credit-check consent
        if (a.isConsentBureau() && "MSME".equals(a.getSegment())) sources.add("GST");
        sources.add("COMMUNICATION");
        for (String s : sources) {
            Terms t = TERMS.get(s);
            repo.save(new Consent(a.getId(), s, t.purpose(), t.scope(), channel, lang, t.validity() == null ? null : now.plus(t.validity())));
        }
        audit.record(by, "consent.recorded", "APPLICATION", a.getAppNo(), a.getId(), String.join(", ", sources));
    }

    /** True when the source may be pulled now. Applications from before the ledger fall back to the form's tick boxes. */
    public boolean allows(LoanApplication a, String source) {
        List<Consent> all = repo.findByApplicationIdOrderByIdAsc(a.getId());
        if (all.isEmpty()) return switch (source) {
            case "KYC" -> a.isConsentKyc();
            case "AA" -> a.isConsentAa();
            case "BUREAU", "GST" -> a.isConsentBureau();
            default -> true;
        };
        return all.stream().anyMatch(c -> c.getSource().equals(source) && c.valid());
    }

    /** Consents that block disbursement if no longer valid (FR-DIS-04 pre-disbursal re-check). */
    public List<String> invalidForDisbursement(LoanApplication a) {
        List<String> out = new ArrayList<>();
        for (String s : List.of("KYC", "AA", "BUREAU"))
            if (repo.findByApplicationIdOrderByIdAsc(a.getId()).stream().anyMatch(c -> c.getSource().equals(s)) && !allows(a, s))
                out.add(s + " consent is no longer valid");
        return out;
    }

    @Transactional
    public Consent withdraw(LoanApplication a, String source, String reason, CurrentUser by) {
        if (reason == null || reason.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Record how the borrower asked (at least 10 characters)");
        Consent c = repo.findByApplicationIdOrderByIdAsc(a.getId()).stream()
                .filter(x -> x.getSource().equals(source) && "GRANTED".equals(x.getStatus())).findFirst()
                .orElseThrow(() -> ApiException.conflict("NO_CONSENT", "There is no active " + source + " consent on this application"));
        c.setStatus("WITHDRAWN");
        c.setWithdrawnAt(Instant.now());
        c.setWithdrawnBy(by.username());
        c.setEffect(EFFECT.get(source));
        repo.save(c);
        audit.record(by, "consent.withdrawn", "APPLICATION", a.getAppNo(), a.getId(), source + " · " + reason.trim() + " · effect: " + c.getEffect());
        return c;
    }
}
