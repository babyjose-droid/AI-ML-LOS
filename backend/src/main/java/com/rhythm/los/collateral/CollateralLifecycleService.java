package com.rhythm.los.collateral;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Fmt;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Collateral after disbursement: the lien, release after closure or a part-payment, and substitution.
 * Operations or credit asks, a credit manager approves (not the person who asked), operations hands the documents back.
 */
@Service
public class CollateralLifecycleService {
    static final Set<Role> MAKERS = Set.of(Role.OPERATIONS, Role.CREDIT_OFFICER, Role.ADMIN);
    static final Set<Role> CHECKERS = Set.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    static final Set<Role> HANDOVER = Set.of(Role.OPERATIONS, Role.ADMIN);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CollateralRepository collaterals;
    private final CollateralChangeRepository changes;
    private final CollateralDocumentRepository documents;
    private final CollateralService service;
    private final LoanBookService book;
    private final LoanApplicationRepository apps;
    private final AuditService audit;

    @Value("${rhythm.lender.name:Rhythm Demo Finance Ltd (NBFC)}") String lenderName = "Rhythm Demo Finance Ltd (NBFC)";

    public CollateralLifecycleService(CollateralRepository collaterals, CollateralChangeRepository changes, CollateralDocumentRepository documents,
                                      CollateralService service, LoanBookService book, LoanApplicationRepository apps, AuditService audit) {
        this.collaterals = collaterals;
        this.changes = changes;
        this.documents = documents;
        this.service = service;
        this.book = book;
        this.apps = apps;
        this.audit = audit;
    }

    public record ReleaseRequest(Long collateralId, String reason) {}
    public record SubstituteRequest(Long collateralId, Long newCollateralId, String reason) {}
    public record Decide(boolean approve, String note) {}
    public record Complete(String handedTo, String note) {}

    static boolean closed(LiveLoan l) { return "CLOSED".equals(l.getStatus()) || l.getOutstanding() != null && l.getOutstanding().signum() == 0; }

    static LocalDate closedOn(LiveLoan l) {
        if (l.getLastPaymentDate() != null) return l.getLastPaymentDate();
        return l.getLastFeedDate() != null ? l.getLastFeedDate() : LocalDate.now(IST);
    }

    List<Collateral> held(LiveLoan l) {
        return collaterals.findByApplicationIdOrderByIdAsc(l.getApplicationId()).stream().filter(c -> "ACTIVE".equals(c.getLienState())).toList();
    }

    CollateralChangeRules.Item item(Collateral c) {
        double maxLtv = service.pol(c.getType()).getMaxLtv().doubleValue();
        return new CollateralChangeRules.Item(c.getId(), c.getColNo(), service.valueOf(c), maxLtv);
    }

    static double outstanding(LiveLoan l) { return l.getOutstanding() == null ? 0 : l.getOutstanding().doubleValue(); }

    static String label(Collateral c) {
        return c.getColNo() + " (" + c.getType().toLowerCase() + (c.getDescription() == null ? "" : ", " + c.getDescription()) + ")";
    }

    // ---------------------------------------------------------------- view

    @Transactional(readOnly = true)
    public Map<String, Object> forLoan(Long loanId, CurrentUser u) {
        LiveLoan l = book.get(loanId, u);
        List<Collateral> all = collaterals.findByApplicationIdOrderByIdAsc(l.getApplicationId());
        List<Collateral> held = all.stream().filter(c -> "ACTIVE".equals(c.getLienState())).toList();
        List<CollateralChangeRules.Item> items = held.stream().map(this::item).toList();
        boolean closed = closed(l);
        double out = outstanding(l);
        List<CollateralChange> cs = changes.findByLoanIdOrderByIdDesc(loanId);
        Set<Long> pending = new HashSet<>();
        cs.stream().filter(x -> Set.of("REQUESTED", "APPROVED").contains(x.getStatus())).forEach(x -> pending.add(x.getCollateralId()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Collateral c : all) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.getId());
            x.put("colNo", c.getColNo());
            x.put("type", c.getType());
            x.put("description", c.getDescription());
            x.put("address", c.getAddress());
            x.put("status", c.getStatus());
            x.put("lienState", c.getLienState());
            x.put("purpose", c.getPurpose());
            x.put("substituteFor", c.getSubstituteFor());
            x.put("releasedAt", c.getReleasedAt());
            x.put("value", Math.round(service.valueOf(c)));
            x.put("maxLtv", service.pol(c.getType()).getMaxLtv());
            if ("ACTIVE".equals(c.getLienState())) {
                CollateralChangeRules.Result r = CollateralChangeRules.release(out, closed, items, c.getId());
                x.put("canRelease", r.ok() && !pending.contains(c.getId()));
                x.put("releaseNote", pending.contains(c.getId()) ? "A request is already open" : r.reason());
            }
            rows.add(x);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanId", l.getId());
        m.put("loanRef", l.getLoanRef());
        m.put("applicationId", l.getApplicationId());
        m.put("status", l.getStatus());
        m.put("closed", closed);
        m.put("outstanding", out);
        Double ltv = CollateralChangeRules.ltv(out, items);
        m.put("ltv", ltv);
        m.put("lendable", Math.round(CollateralChangeRules.lendable(items)));
        if (closed) {
            LocalDate due = CollateralChangeRules.releaseDue(closedOn(l));
            m.put("closedOn", closedOn(l));
            m.put("releaseDue", due);
            boolean anyHeld = !held.isEmpty();
            m.put("releaseOverdueDays", anyHeld ? CollateralChangeRules.daysLate(due, LocalDate.now(IST)) : 0);
            m.put("compensationIfLate", anyHeld ? CollateralChangeRules.compensation(due, LocalDate.now(IST)) : 0);
        }
        m.put("collaterals", rows);
        m.put("changes", cs);
        m.put("rule", "Documents go back within " + CollateralChangeRules.RELEASE_DAYS + " days of closure; each day late costs ₹"
                + CollateralChangeRules.DELAY_COMPENSATION_PER_DAY + " (RBI directions on release of property documents, 2023). Before closure, what remains must cover the outstanding within each type's maximum LTV.");
        return m;
    }

    // ---------------------------------------------------------------- release

    @Transactional
    public CollateralChange requestRelease(Long loanId, ReleaseRequest r, CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Operations or credit asks for a release");
        LiveLoan l = book.get(loanId, by);
        Collateral c = heldOne(l, r.collateralId());
        String reason = reason(r.reason());
        openCheck(loanId, c.getId());
        boolean closed = closed(l);
        double out = outstanding(l);
        List<CollateralChangeRules.Item> items = held(l).stream().map(this::item).toList();
        CollateralChangeRules.Result res = CollateralChangeRules.release(out, closed, items, c.getId());
        if (!res.ok()) throw ApiException.unprocessable("COVER_SHORT", res.reason());
        CollateralChange x = base(l, "RELEASE", c, reason, out, res, by);
        if (closed) {
            // nothing is at risk once the loan is repaid: the release is due, not a credit decision
            x.setStatus("APPROVED");
            x.setDecidedBy("system");
            x.setDecidedAt(Instant.now());
            x.setDecisionNote("Loan closed: release is due");
            x.setDueOn(CollateralChangeRules.releaseDue(closedOn(l)));
        }
        changes.save(x);
        audit.record(by, "collateral.release_requested", "LOAN", l.getLoanRef(), l.getApplicationId(), c.getColNo() + " · " + res.reason() + " · " + reason);
        return x;
    }

    // ---------------------------------------------------------------- substitution

    /** The borrower offers another property in place of one held: it is declared, read, photographed and valued like any collateral. */
    @Transactional
    public Collateral declareSubstitute(Long loanId, Long collateralId, CollateralService.Declare r, CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Operations or credit records a substitute");
        LiveLoan l = book.get(loanId, by);
        if (closed(l)) throw ApiException.conflict("LOAN_CLOSED", "The loan is closed: release the collateral instead");
        Collateral old = heldOne(l, collateralId);
        LoanApplication a = apps.findById(l.getApplicationId()).orElseThrow(() -> ApiException.notFound("Application"));
        Collateral c = service.create(a, r, by, old);
        audit.record(by, "collateral.substitute_declared", "LOAN", l.getLoanRef(), l.getApplicationId(), c.getColNo() + " offered in place of " + old.getColNo());
        return c;
    }

    @Transactional
    public CollateralChange requestSubstitution(Long loanId, SubstituteRequest r, CurrentUser by) {
        if (!MAKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Operations or credit asks for a substitution");
        LiveLoan l = book.get(loanId, by);
        if (closed(l)) throw ApiException.conflict("LOAN_CLOSED", "The loan is closed: release the collateral instead");
        Collateral old = heldOne(l, r.collateralId());
        Collateral neu = substitute(l, old, r.newCollateralId());
        String reason = reason(r.reason());
        openCheck(loanId, old.getId());
        double out = outstanding(l);
        CollateralChangeRules.Result res = check(l, old, neu);
        CollateralChange x = base(l, "SUBSTITUTE", old, reason, out, res, by);
        x.setNewCollateralId(neu.getId());
        changes.save(x);
        audit.record(by, "collateral.substitution_requested", "LOAN", l.getLoanRef(), l.getApplicationId(), old.getColNo() + " → " + neu.getColNo() + " · " + res.reason());
        return x;
    }

    Collateral substitute(LiveLoan l, Collateral old, Long newId) {
        Collateral neu = collaterals.findById(newId == null ? -1 : newId).orElseThrow(() -> ApiException.notFound("Substitute collateral"));
        if (!neu.getApplicationId().equals(l.getApplicationId()) || !"SUBSTITUTE".equals(neu.getPurpose()) || !old.getId().equals(neu.getSubstituteFor()))
            throw ApiException.unprocessable("NOT_A_SUBSTITUTE", neu.getColNo() + " was not offered in place of " + old.getColNo());
        if (!"NONE".equals(neu.getLienState())) throw ApiException.conflict("ALREADY_USED", neu.getColNo() + " is " + neu.getLienState().toLowerCase());
        return neu;
    }

    CollateralChangeRules.Result check(LiveLoan l, Collateral old, Collateral neu) {
        if (!"PASS".equals(neu.getStatus()))
            throw ApiException.unprocessable("SUBSTITUTE_NOT_CLEARED", neu.getColNo() + " must pass the collateral checks first (now " + neu.getStatus().toLowerCase() + ")");
        List<CollateralChangeRules.Item> items = held(l).stream().map(this::item).toList();
        CollateralChangeRules.Result res = CollateralChangeRules.substitute(outstanding(l), items, old.getId(), item(neu));
        if (!res.ok()) throw ApiException.unprocessable("COVER_SHORT", res.reason());
        return res;
    }

    // ---------------------------------------------------------------- approval and handover

    @Transactional
    public CollateralChange decide(Long changeId, Decide d, CurrentUser by) {
        if (!CHECKERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "A credit manager or the CRO approves releases and substitutions");
        CollateralChange x = changes.findById(changeId).orElseThrow(() -> ApiException.notFound("Request"));
        LiveLoan l = book.get(x.getLoanId(), by);
        if (!"REQUESTED".equals(x.getStatus())) throw ApiException.conflict("NOT_OPEN", "This request is " + x.getStatus().toLowerCase());
        if (by.username().equals(x.getRequestedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who asked cannot approve");
        String note = d.note() == null ? "" : d.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Say why (at least 10 characters)");
        if (d.approve()) {
            // the loan may have moved since the request: check again
            Collateral old = collaterals.findById(x.getCollateralId()).orElseThrow();
            if ("SUBSTITUTE".equals(x.getKind())) check(l, old, collaterals.findById(x.getNewCollateralId()).orElseThrow());
            else {
                CollateralChangeRules.Result res = CollateralChangeRules.release(outstanding(l), closed(l), held(l).stream().map(this::item).toList(), old.getId());
                if (!res.ok()) throw ApiException.unprocessable("COVER_SHORT", res.reason());
            }
        }
        x.setStatus(d.approve() ? "APPROVED" : "REJECTED");
        x.setDecidedBy(by.username());
        x.setDecidedAt(Instant.now());
        x.setDecisionNote(note.length() > 300 ? note.substring(0, 300) : note);
        changes.save(x);
        audit.record(by, "collateral." + x.getKind().toLowerCase() + (d.approve() ? "_approved" : "_rejected"), "LOAN", l.getLoanRef(), l.getApplicationId(), note);
        return x;
    }

    @Transactional
    public CollateralChange complete(Long changeId, Complete r, CurrentUser by) {
        if (!HANDOVER.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Operations hands the documents back");
        CollateralChange x = changes.findById(changeId).orElseThrow(() -> ApiException.notFound("Request"));
        LiveLoan l = book.get(x.getLoanId(), by);
        if (!"APPROVED".equals(x.getStatus())) throw ApiException.conflict("NOT_APPROVED", "This request is " + x.getStatus().toLowerCase());
        String to = r.handedTo() == null ? "" : r.handedTo().trim();
        if (to.length() < 3) throw ApiException.unprocessable("HANDED_TO_REQUIRED", "Name who received the original documents");
        Collateral old = collaterals.findById(x.getCollateralId()).orElseThrow();
        Collateral neu = null;
        if ("SUBSTITUTE".equals(x.getKind())) {
            neu = collaterals.findById(x.getNewCollateralId()).orElseThrow();
            check(l, old, neu);
            neu.setLienState("ACTIVE");
            collaterals.save(neu);
        }
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(IST);
        old.setLienState("SUBSTITUTE".equals(x.getKind()) ? "SUBSTITUTED" : "RELEASED");
        old.setReleasedAt(now);
        collaterals.save(old);
        x.setStatus("COMPLETED");
        x.setCompletedBy(by.username());
        x.setCompletedAt(now);
        x.setHandedTo(to.length() > 120 ? to.substring(0, 120) : to);
        if (x.getDueOn() != null) {
            x.setDaysLate((int) CollateralChangeRules.daysLate(x.getDueOn(), today));
            x.setCompensation(BigDecimal.valueOf(CollateralChangeRules.compensation(x.getDueOn(), today)).setScale(2, RoundingMode.HALF_UP));
        }
        x.setLetter(letter(l, old, neu, x, today));
        changes.save(x);
        audit.record(by, "collateral." + ("SUBSTITUTE".equals(x.getKind()) ? "substituted" : "released"), "LOAN", l.getLoanRef(), l.getApplicationId(),
                old.getColNo() + (neu == null ? "" : " → " + neu.getColNo()) + " · documents to " + to
                        + (x.getDaysLate() != null && x.getDaysLate() > 0 ? " · " + x.getDaysLate() + " days late, compensation " + Fmt.rupees(x.getCompensation().doubleValue()) : ""));
        return x;
    }

    String letter(LiveLoan l, Collateral old, Collateral neu, CollateralChange x, LocalDate today) {
        List<String> docs = documents.findByCollateralIdOrderByIdAsc(old.getId()).stream().map(d -> d.getDocType().replace('_', ' ').toLowerCase()).distinct().toList();
        StringBuilder b = new StringBuilder();
        b.append(lenderName).append('\n').append(today).append("\n\n");
        b.append(neu == null ? "Release of security" : "Release of security on substitution").append(" · loan ").append(l.getLoanRef()).append("\n\n");
        b.append("Dear ").append(l.getBorrowerName()).append(",\n\n");
        b.append("We release our charge on ").append(label(old)).append(old.getAddress() == null ? "" : ", " + old.getAddress()).append(", given as security for loan ")
                .append(l.getLoanRef()).append(". ");
        if (neu != null) b.append("It is replaced by ").append(label(neu)).append(neu.getAddress() == null ? "" : ", " + neu.getAddress()).append(", which now secures the loan. ");
        else if (closed(l)) b.append("The loan is fully repaid and we have no further claim on this property. ");
        else b.append("The loan stays open and is secured by the remaining collateral. ");
        b.append("\n\nThe original documents").append(docs.isEmpty() ? "" : " (" + String.join(", ", docs) + ")").append(" were handed to ").append(x.getHandedTo())
                .append(" on ").append(today).append(". Please have any charge registered with CERSAI or the sub-registrar satisfied; we will file the satisfaction within 30 days.");
        if (x.getDaysLate() != null && x.getDaysLate() > 0)
            b.append("\n\nThe documents were due on ").append(x.getDueOn()).append(". For the delay of ").append(x.getDaysLate()).append(" days we will pay you ")
                    .append(Fmt.rupees(x.getCompensation().doubleValue())).append(" (₹").append(CollateralChangeRules.DELAY_COMPENSATION_PER_DAY).append(" a day).");
        b.append("\n\nAuthorised signatory");
        return b.toString();
    }

    // ---------------------------------------------------------------- helpers

    Collateral heldOne(LiveLoan l, Long collateralId) {
        Collateral c = collaterals.findById(collateralId == null ? -1 : collateralId).orElseThrow(() -> ApiException.notFound("Collateral"));
        if (!c.getApplicationId().equals(l.getApplicationId())) throw ApiException.notFound("Collateral");
        if (!"ACTIVE".equals(c.getLienState())) throw ApiException.conflict("NOT_HELD", c.getColNo() + " is not held against this loan (" + c.getLienState().toLowerCase() + ")");
        return c;
    }

    void openCheck(Long loanId, Long collateralId) {
        if (changes.findByLoanIdOrderByIdDesc(loanId).stream().anyMatch(x -> collateralId.equals(x.getCollateralId()) && Set.of("REQUESTED", "APPROVED").contains(x.getStatus())))
            throw ApiException.conflict("ALREADY_REQUESTED", "A request for this collateral is already open");
    }

    static String reason(String r) {
        String s = r == null ? "" : r.trim();
        if (s.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Say why (at least 10 characters)");
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    CollateralChange base(LiveLoan l, String kind, Collateral c, String reason, double out, CollateralChangeRules.Result res, CurrentUser by) {
        CollateralChange x = new CollateralChange();
        x.setLoanId(l.getId());
        x.setApplicationId(l.getApplicationId());
        x.setKind(kind);
        x.setCollateralId(c.getId());
        x.setReason(reason);
        x.setOutstandingAt(BigDecimal.valueOf(out).setScale(2, RoundingMode.HALF_UP));
        x.setLtvBefore(BigDecimal.valueOf(res.ltvBefore()).setScale(4, RoundingMode.HALF_UP));
        x.setLtvAfter(res.ltvAfter() == null ? null : BigDecimal.valueOf(res.ltvAfter()).setScale(4, RoundingMode.HALF_UP));
        x.setCheckNote(res.reason().length() > 400 ? res.reason().substring(0, 400) : res.reason());
        x.setRequestedBy(by.username());
        return x;
    }
}
