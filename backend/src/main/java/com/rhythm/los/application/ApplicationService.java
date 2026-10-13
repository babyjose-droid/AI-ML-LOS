package com.rhythm.los.application;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.flow.WorkflowDefinitions;
import com.rhythm.los.product.Product;
import com.rhythm.los.product.ProductRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class ApplicationService {
    private final LoanApplicationRepository apps;
    private final StateHistoryRepository history;
    private final ProductRepository products;
    private final AuditService audit;
    private final WorkflowDefinitions workflows;
    private final ApplicationEventPublisher events;
    private final com.rhythm.los.product.ProductService productVersions;
    private final com.rhythm.los.consent.ConsentService consents;

    public ApplicationService(LoanApplicationRepository apps, StateHistoryRepository history,
                              ProductRepository products, AuditService audit, WorkflowDefinitions workflows, ApplicationEventPublisher events,
                              com.rhythm.los.product.ProductService productVersions, com.rhythm.los.consent.ConsentService consents) {
        this.productVersions = productVersions;
        this.consents = consents;
        this.apps = apps;
        this.history = history;
        this.products = products;
        this.audit = audit;
        this.workflows = workflows;
        this.events = events;
    }

    public LoanApplication get(Long id) {
        return apps.findById(id).orElseThrow(() -> ApiException.notFound("Application " + id));
    }

    /** The product as it was when the application was created (its pinned version), never a later edit. */
    public Product product(LoanApplication a) {
        return productVersions.definition(a.getProductCode(), a.getProductVersion());
    }

    /** The only way a state changes. Invalid moves are refused and nothing is written. */
    @Transactional
    public void transition(LoanApplication a, Domain d, String to, String event, CurrentUser by, String note) {
        String from = a.state(d);
        if (!StateMachine.allowed(d, from, to)) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    d + " cannot move from " + from + " to " + to + " (event " + event + ")");
        }
        a.setState(d, to);
        apps.save(a);
        history.save(new StateHistory(a.getId(), d, from, to, event, by.username(), note));
        audit.record(by, event, "APPLICATION", a.getAppNo(), a.getId(), d + ": " + from + " -> " + to + (note == null ? "" : " · " + note));
        events.publishEvent(new ApplicationChanged(a.getId(), by.username()));
    }

    @Transactional
    public LoanApplication create(ApplicationRequest r, CurrentUser by) {
        Product p = products.findByCode(r.productCode())
                .filter(x -> "LIVE".equals(x.getStatus()))
                .orElseThrow(() -> ApiException.unprocessable("PRODUCT_NOT_LIVE", "Product " + r.productCode() + " is not available"));
        LoanApplication a = new LoanApplication();
        apply(a, r, p);
        a.setCreatedBy(by.username());
        a.setBranchId(by.branchId());
        workflows.published(p.getCode()).ifPresent(w -> a.setWorkflowVersion(w.getVersion()));
        a.setProductVersion(p.getVersion());
        if (apps.countActiveByPanExcluding(a.getPan(), -1L) > 0) {
            throw ApiException.conflict("DUPLICATE_PAN", "An active application already exists for PAN " + com.rhythm.los.common.Mask.pan(a.getPan()));
        }
        LoanApplication saved = apps.save(a);
        saved.setAppNo(String.format("APP-%06d", 300000 + saved.getId()));
        saved = apps.save(saved);
        history.save(new StateHistory(saved.getId(), Domain.APP, "-", "DRAFT", "application.created", by.username(), p.getName()));
        audit.record(by, "application.created", "APPLICATION", saved.getAppNo(), saved.getId(), p.getCode() + " · ₹" + saved.getLoanAmount()
                + (saved.getWorkflowVersion() == null ? "" : " · workflow v" + saved.getWorkflowVersion()));
        events.publishEvent(new ApplicationChanged(saved.getId(), by.username()));
        return saved;
    }

    @Transactional
    public LoanApplication update(Long id, ApplicationRequest r, CurrentUser by) {
        LoanApplication a = get(id);
        if (!"DRAFT".equals(a.getAppState())) throw ApiException.conflict("NOT_EDITABLE", "Only draft applications can be edited");
        Product p = products.findByCode(r.productCode()).orElseThrow(() -> ApiException.notFound("Product"));
        boolean productChanged = !p.getCode().equals(a.getProductCode());
        apply(a, r, p);
        // a draft moved to another product takes that product's published workflow; nothing has started yet
        if (productChanged) a.setWorkflowVersion(workflows.published(p.getCode()).map(w -> w.getVersion()).orElse(null));
        a.setProductVersion(p.getVersion());
        if (apps.countActiveByPanExcluding(a.getPan(), a.getId()) > 0) {
            throw ApiException.conflict("DUPLICATE_PAN", "An active application already exists for PAN " + com.rhythm.los.common.Mask.pan(a.getPan()));
        }
        audit.record(by, "application.updated", "APPLICATION", a.getAppNo(), a.getId(), null);
        return apps.save(a);
    }

    @Transactional
    public LoanApplication submit(Long id, CurrentUser by) {
        LoanApplication a = get(id);
        if (!a.isConsentBureau() || !a.isConsentAa() || !a.isConsentKyc()) {
            throw ApiException.unprocessable("CONSENT_MISSING", "Bureau, Account Aggregator and KYC consents are all needed before submitting");
        }
        if (a.getBankAccountNo() == null || a.getBankIfsc() == null) {
            throw ApiException.unprocessable("BANK_DETAILS_MISSING", "Bank account and IFSC are needed for disbursement");
        }
        Product p = product(a);
        transition(a, Domain.APP, "SUBMITTED", "application.submitted", by, null);
        consents.recordFromApplication(a, by);
        boolean existing = apps.countDisbursedByPanExcluding(a.getPan(), a.getId()) > 0;
        boolean field = p.fieldVisitNeeded(a.getLoanAmount().doubleValue()) && workflows.fieldStageApplies(a, existing);
        transition(a, Domain.FIELD, field ? "REQUIRED" : "NOT_REQUIRED", "field.rule_evaluated", CurrentUser.SYSTEM,
                "Rule " + p.getFieldVisitRule() + (p.getFieldVisitThreshold() != null ? " ₹" + p.getFieldVisitThreshold() : "")
                        + (p.fieldVisitNeeded(a.getLoanAmount().doubleValue()) && !field ? " · skipped by workflow rule" : ""));
        return a;
    }

    @Transactional
    public LoanApplication withdraw(Long id, String reason, CurrentUser by) {
        LoanApplication a = get(id);
        transition(a, Domain.APP, "WITHDRAWN", "application.withdrawn", by, reason);
        return a;
    }

    @Transactional
    public LoanApplication recordFieldVisit(Long id, String note, CurrentUser by) {
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Write field visit notes of at least 10 characters");
        LoanApplication a = get(id);
        transition(a, Domain.FIELD, "DONE", "field.visit_completed", by, note.trim());
        return a;
    }

    @Transactional
    public void reject(LoanApplication a, String reason, CurrentUser by) {
        a.setRejectionReason(reason);
        transition(a, Domain.APP, "REJECTED", "application.rejected", by, reason);
    }

    public List<StateHistory> history(Long id) { return history.findByApplicationIdOrderByIdAsc(id); }

    private static final Set<String> CLOSED = Set.of("REJECTED", "WITHDRAWN", "DISBURSED");

    public static boolean isOpen(LoanApplication a) { return !CLOSED.contains(a.getAppState()); }

    /** Stored as FEMALE, MALE or OTHER; anything else (blank, "Prefer not to say") is not stored. */
    static String gender(String g) {
        if (g == null) return null;
        String x = g.trim().toUpperCase();
        return switch (x) { case "F", "FEMALE" -> "FEMALE"; case "M", "MALE" -> "MALE"; case "O", "OTHER", "TRANSGENDER" -> "OTHER"; default -> null; };
    }

    private void apply(LoanApplication a, ApplicationRequest r, Product p) {
        double amt = r.loanAmount().doubleValue();
        if (amt < p.getMinAmount().doubleValue() || amt > p.getMaxAmount().doubleValue()) {
            throw ApiException.unprocessable("AMOUNT_OUT_OF_RANGE",
                    "Loan amount must be between ₹" + p.getMinAmount().toPlainString() + " and ₹" + p.getMaxAmount().toPlainString() + " for " + p.getName());
        }
        if (r.tenureMonths() < p.getMinTenure() || r.tenureMonths() > p.getMaxTenure()) {
            throw ApiException.unprocessable("TENURE_OUT_OF_RANGE",
                    "Tenure must be between " + p.getMinTenure() + " and " + p.getMaxTenure() + " months for " + p.getName());
        }
        a.setProductCode(p.getCode());
        a.setSegment(p.getSegment());
        a.setApplicantName(r.applicantName().trim());
        a.setPan(r.pan().toUpperCase());
        a.setMobile(r.mobile());
        a.setEmail(r.email());
        a.setDob(r.dob());
        a.setGender(gender(r.gender()));
        if (r.preferredLanguage() != null && !r.preferredLanguage().isBlank()) a.setPreferredLanguage(r.preferredLanguage());
        a.setAddress(r.address());
        a.setCity(r.city());
        a.setPincode(blankToNull(r.pincode()));
        a.setBusinessName(r.businessName());
        a.setBusinessVintageYears(r.businessVintageYears());
        a.setDeclaredMonthlyIncome(r.declaredMonthlyIncome());
        a.setEssentialExpenses(r.essentialExpenses());
        a.setLoanAmount(r.loanAmount());
        a.setTenureMonths(r.tenureMonths());
        a.setPurpose(r.purpose());
        a.setBankAccountNo(blankToNull(r.bankAccountNo()));
        a.setBankIfsc(blankToNull(r.bankIfsc()));
        a.setConsentBureau(r.consentBureau());
        a.setConsentAa(r.consentAa());
        a.setConsentKyc(r.consentKyc());
        if (r.consentBureau() && r.consentAa() && r.consentKyc() && a.getConsentAt() == null) a.setConsentAt(Instant.now());
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }
}
