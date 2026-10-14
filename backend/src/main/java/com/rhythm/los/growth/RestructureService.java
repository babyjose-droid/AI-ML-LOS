package com.rhythm.los.growth;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LiveLoanRepository;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.org.Role;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * Restructuring with maker-checker. The maker proposes a tenure extension, a moratorium or a rate cut with a new
 * schedule; a credit manager or the CRO (never the maker) approves. On approval the arrears are capitalised, the
 * schedule replaces the old one and, as the RBI prudential framework (7 June 2019) requires, a standard account is
 * downgraded to NPA and can be upgraded only after a year of satisfactory repayment from the first instalment due
 * under the new terms (a slip of more than 30 days restarts that year). ECL moves to stage 3.
 */
@Service
public class RestructureService {
    public record Request(String kind, Integer newTenureMonths, Double newRate, Integer moratoriumMonths, String reason) {}

    private final LoanRestructureRepository repo;
    private final LiveLoanRepository loans;
    private final LoanBookService book;
    private final AuditService audit;

    public RestructureService(LoanRestructureRepository repo, LiveLoanRepository loans, LoanBookService book, AuditService audit) {
        this.repo = repo;
        this.loans = loans;
        this.book = book;
        this.audit = audit;
    }

    static BigDecimal money(double v) { return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP); }

    /** Schedule after the moratorium: interest accrues and is capitalised during the moratorium, then equal instalments. */
    public static List<Map<String, Object>> schedule(double principal, double rate, int moratorium, int totalMonths, LocalDate start) {
        double r = rate / 1200, p = principal;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int m = 1; m <= moratorium; m++) {
            double i = p * r;
            p += i;
            rows.add(row(m, start.plusMonths(m), 0, i, 0, p));
        }
        int n = totalMonths - moratorium;
        double emi = n <= 0 ? 0 : r == 0 ? p / n : p * r * Math.pow(1 + r, n) / (Math.pow(1 + r, n) - 1);
        for (int m = 1; m <= n; m++) {
            double i = p * r, pr = Math.min(p, emi - i);
            p = Math.max(0, p - pr);
            rows.add(row(moratorium + m, start.plusMonths(moratorium + m), emi, i, pr, p));
        }
        return rows;
    }

    static Map<String, Object> row(int no, LocalDate due, double emi, double interest, double principal, double balance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("no", no); m.put("due", due.toString()); m.put("emi", Math.round(emi)); m.put("interest", Math.round(interest));
        m.put("principal", Math.round(principal)); m.put("balance", Math.round(balance));
        return m;
    }

    @Transactional
    public LoanRestructure request(Long loanId, Request r, CurrentUser by) {
        LiveLoan l = book.get(loanId, by);
        if (!"ACTIVE".equals(l.getStatus())) throw ApiException.conflict("NOT_ACTIVE", "Only an active loan can be restructured");
        if (repo.findByLoanIdOrderByIdDesc(loanId).stream().anyMatch(x -> "PENDING".equals(x.getStatus())))
            throw ApiException.conflict("PENDING_EXISTS", "A restructuring proposal for this loan is already waiting for approval");
        if (r.kind() == null || !Set.of("TENURE_EXTENSION", "MORATORIUM", "RATE_REDUCTION").contains(r.kind()))
            throw ApiException.unprocessable("BAD_KIND", "Kind must be TENURE_EXTENSION, MORATORIUM or RATE_REDUCTION");
        if (r.reason() == null || r.reason().isBlank() || r.reason().length() > 500) throw ApiException.unprocessable("REASON_NEEDED", "Give the reason (the borrower's cash flow problem)");
        LocalDate on = LoanBookService.asOf(l);
        int remaining = Math.max(1, l.getTenureMonths() - l.monthsOnBook(on));
        int mor = r.moratoriumMonths() == null ? 0 : r.moratoriumMonths();
        int tenure = r.newTenureMonths() == null ? remaining : r.newTenureMonths();
        double rate = r.newRate() == null ? l.getRate().doubleValue() : r.newRate();
        if (mor < 0 || mor > 6) throw ApiException.unprocessable("BAD_MORATORIUM", "Moratorium is 0 to 6 months");
        if ("MORATORIUM".equals(r.kind()) && mor == 0) throw ApiException.unprocessable("BAD_MORATORIUM", "A moratorium needs at least one month");
        if (tenure < remaining || tenure > remaining + 36 || tenure > 120) throw ApiException.unprocessable("BAD_TENURE", "New remaining tenure must be " + remaining + " to " + Math.min(120, remaining + 36) + " months");
        if (tenure <= mor) throw ApiException.unprocessable("BAD_TENURE", "Tenure must be longer than the moratorium");
        if (rate > l.getRate().doubleValue() || rate < 6) throw ApiException.unprocessable("BAD_RATE", "The new rate cannot exceed the current " + l.getRate() + "%");
        double principal = l.getOutstanding().doubleValue();  // the LMS outstanding includes the overdue principal
        List<Map<String, Object>> sched = schedule(principal, rate, mor, tenure, on);
        LoanRestructure x = new LoanRestructure();
        x.setLoanId(loanId);
        x.setKind(r.kind());
        x.setNewTenureMonths(tenure);
        x.setNewRate(money(rate));
        x.setMoratoriumMonths(mor);
        x.setNewEmi(money(((Number) sched.get(sched.size() - 1).get("emi")).doubleValue()));
        x.setOutstandingAt(l.getOutstanding());
        x.setReason(r.reason().trim());
        x.setRequestedBy(by.username());
        x.setScheduleJson(Json.write(sched));
        repo.save(x);
        audit.record(by, "restructure.requested", "LOAN", l.getLoanRef(), l.getApplicationId(), r.kind() + " · " + tenure + " months at " + rate + "% · EMI ₹" + x.getNewEmi()
                + " (was ₹" + l.getEmi() + ")");
        return x;
    }

    @Transactional
    public LoanRestructure decide(Long id, boolean approve, String note, CurrentUser by) {
        LoanRestructure x = repo.findById(id).orElseThrow(() -> ApiException.notFound("Restructuring proposal"));
        LiveLoan l = book.get(x.getLoanId(), by);
        if (!"PENDING".equals(x.getStatus())) throw ApiException.conflict("ALREADY_DECIDED", "This proposal was already " + x.getStatus().toLowerCase());
        if (!Set.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN).contains(by.role())) throw ApiException.forbidden("CHECKER_ROLE", "A credit manager or the CRO approves restructuring");
        if (by.username().equals(x.getRequestedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who proposed the restructuring cannot approve it");
        x.setStatus(approve ? "APPROVED" : "REJECTED");
        x.setDecidedBy(by.username());
        x.setDecidedAt(Instant.now());
        x.setDecisionNote(note);
        repo.save(x);
        if (!approve) {
            audit.record(by, "restructure.rejected", "LOAN", l.getLoanRef(), l.getApplicationId(), note);
            return x;
        }
        LocalDate on = LoanBookService.asOf(l);
        l.setTenureMonths(l.monthsOnBook(on) + x.getNewTenureMonths());
        l.setRate(x.getNewRate());
        l.setEmi(x.getNewEmi());
        l.setOverdue(BigDecimal.ZERO);   // arrears capitalised into the new schedule
        l.setDpd(0);
        l.setRestructured(true);
        l.setRestructuredOn(on);
        if (!"NPA".equals(l.getClassification())) {
            l.setClassification("NPA");
            l.setNpaSince(on);
        }
        // specified period: a year from the first instalment due under the new terms
        l.setNpaHoldUntil(on.plusMonths(x.getMoratoriumMonths() + 1L).plusMonths(12));
        book.refresh(l, on);
        audit.record(by, "restructure.approved", "LOAN", l.getLoanRef(), l.getApplicationId(), x.getKind() + " approved; classified NPA (restructured) until "
                + l.getNpaHoldUntil() + (note == null || note.isBlank() ? "" : " · " + note));
        return x;
    }

    public List<Map<String, Object>> pending(CurrentUser u) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (LoanRestructure x : repo.findByStatusOrderByIdAsc("PENDING")) {
            LiveLoan l = loans.findById(x.getLoanId()).orElse(null);
            if (l == null) continue;
            try { book.get(l.getId(), u); } catch (ApiException e) { continue; }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("proposal", x);
            m.put("loanRef", l.getLoanRef());
            m.put("borrowerName", l.getBorrowerName());
            m.put("currentEmi", l.getEmi());
            m.put("dpd", l.getDpd());
            m.put("classification", l.getClassification());
            out.add(m);
        }
        return out;
    }

    public List<LoanRestructure> forLoan(Long loanId, CurrentUser u) {
        book.get(loanId, u);
        return repo.findByLoanIdOrderByIdDesc(loanId);
    }
}
