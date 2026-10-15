package com.rhythm.los.growth;

import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Collections work queue: every overdue loan (and every loan on the early-warning list before it is due), ranked,
 * with propensity to pay, the next channel, the best hour and a script. Contacts are logged; calls and visits only
 * between 8 am and 7 pm India time. Promises to pay are marked kept or broken from the LMS feed.
 */
@Service
public class CollectionsService {
    public static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    public record Contact(String channel, String outcome, String contactedAt, String ptpDate, Double ptpAmount, String note, Long partyId) {
        public Contact(String channel, String outcome, String contactedAt, String ptpDate, Double ptpAmount, String note) { this(channel, outcome, contactedAt, ptpDate, ptpAmount, note, null); }
    }
    /** A guarantor is approached only once the borrower is 30+ days overdue or has broken a promise; co-applicants owe the whole loan and may be contacted any time. */
    public static final int GUARANTOR_DPD = 30;

    static String guarantorBlock(int dpd, long brokenPromises) {
        return dpd >= GUARANTOR_DPD || brokenPromises > 0 ? null
                : "A guarantor is contacted only after the borrower is " + GUARANTOR_DPD + "+ days overdue or breaks a promise to pay (now " + dpd + " DPD, no broken promise)";
    }

    static final Set<String> CHANNELS = Set.of("SMS", "WHATSAPP", "CALL", "FIELD_VISIT", "NOTICE");
    static final Set<String> OUTCOMES = Set.of("PROMISE_TO_PAY", "PAID", "NO_ANSWER", "REFUSED", "DISPUTE", "WRONG_NUMBER", "SENT");
    static final Set<String> ANSWERED = Set.of("PROMISE_TO_PAY", "PAID", "REFUSED", "DISPUTE");

    private final CollectionContactRepository contacts;
    private final LoanBookService book;
    private final AuditService audit;

    public CollectionsService(CollectionContactRepository contacts, LoanBookService book, AuditService audit) {
        this.contacts = contacts;
        this.book = book;
        this.audit = audit;
    }

    /** Open promises whose date (plus 3 days' grace) has passed: kept if the loan was paid in between, else broken. */
    void refreshPromises(LiveLoan l, List<CollectionContact> cs) {
        LocalDate on = LoanBookService.asOf(l);
        for (CollectionContact c : cs) {
            if (!"OPEN".equals(c.getPtpStatus()) || c.getPtpDate() == null) continue;
            LocalDate made = c.getContactedAt().atZone(IST).toLocalDate();
            boolean paid = l.getLastPaymentDate() != null && !l.getLastPaymentDate().isBefore(made) && !l.getLastPaymentDate().isAfter(c.getPtpDate().plusDays(3));
            if (paid || (l.getOverdue().signum() == 0 && l.getDpd() == 0)) c.setPtpStatus("KEPT");
            else if (on.isAfter(c.getPtpDate().plusDays(3))) c.setPtpStatus("BROKEN");
            else continue;
            contacts.save(c);
        }
    }

    Map<String, Object> view(LiveLoan l, List<CollectionContact> cs) {
        refreshPromises(l, cs);
        LocalDate on = LoanBookService.asOf(l);
        int kept = (int) cs.stream().filter(c -> "KEPT".equals(c.getPtpStatus())).count();
        int broken = (int) cs.stream().filter(c -> "BROKEN".equals(c.getPtpStatus())).count();
        boolean open = cs.stream().anyMatch(c -> "OPEN".equals(c.getPtpStatus()));
        int since = l.getLastPaymentDate() == null ? (int) Math.min(180, ChronoUnit.DAYS.between(l.getDisbursedOn(), on)) : (int) ChronoUnit.DAYS.between(l.getLastPaymentDate(), on);
        double bpd = l.getBehaviourPd() == null ? 0.05 : l.getBehaviourPd().doubleValue();
        double p = CollectionsAi.propensity(l.getDpd(), l.getBounces3m(), bpd, l.getPaidEmis(), kept, broken, Math.max(0, since));
        Integer hour = cs.stream().filter(c -> ANSWERED.contains(c.getOutcome()) && !"SMS".equals(c.getChannel()))
                .collect(Collectors.groupingBy(c -> c.getContactedAt().atZone(IST).getHour(), Collectors.counting()))
                .entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        CollectionsAi.Recommendation rec = CollectionsAi.recommend(l.getDpd(), l.getEwsScore(), p, broken, l.getSegment(), hour, open);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loanId", l.getId());
        m.put("loanRef", l.getLoanRef());
        m.put("borrowerName", l.getBorrowerName());
        m.put("segment", l.getSegment());
        m.put("outstanding", l.getOutstanding());
        m.put("overdue", l.getOverdue());
        m.put("dpd", l.getDpd());
        m.put("classification", l.getClassification());
        m.put("ewsScore", l.getEwsScore());
        m.put("recommendation", rec);
        m.put("ptpKept", kept);
        m.put("ptpBroken", broken);
        m.put("openPromise", cs.stream().filter(c -> "OPEN".equals(c.getPtpStatus())).findFirst().orElse(null));
        m.put("lastContact", cs.stream().findFirst().orElse(null));
        return m;
    }

    @Transactional
    public List<Map<String, Object>> queue(CurrentUser u) {
        List<LiveLoan> ls = book.visible(u).stream()
                .filter(l -> "ACTIVE".equals(l.getStatus()) && (l.getDpd() > 0 || l.getEwsScore() >= 40)).toList();
        Map<Long, List<CollectionContact>> byLoan = contacts.findAll().stream()
                .sorted(Comparator.comparing(CollectionContact::getId).reversed())
                .collect(Collectors.groupingBy(CollectionContact::getLoanId, LinkedHashMap::new, Collectors.toList()));
        return ls.stream().map(l -> view(l, byLoan.getOrDefault(l.getId(), new ArrayList<>())))
                .sorted(Comparator.comparingInt((Map<String, Object> m) -> ((CollectionsAi.Recommendation) m.get("recommendation")).priority()).reversed())
                .limit(300).toList();
    }

    @Transactional
    public Map<String, Object> forLoan(Long loanId, CurrentUser u) {
        LiveLoan l = book.get(loanId, u);
        List<CollectionContact> cs = new ArrayList<>(contacts.findByLoanIdOrderByIdDesc(loanId));
        Map<String, Object> m = view(l, cs);
        m.put("contacts", cs);
        long broken = cs.stream().filter(c -> "BROKEN".equals(c.getPtpStatus())).count();
        String block = guarantorBlock(l.getDpd(), broken);
        m.put("parties", book.parties(loanId).stream().map(p -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", p.getId());
            x.put("role", p.getRole());
            x.put("partyType", p.getPartyType());
            x.put("name", p.getName());
            x.put("signatory", p.getSignatoryName());
            x.put("relationship", p.getRelationship());
            x.put("mobile", p.getMobile());
            x.put("pan", com.rhythm.los.common.Mask.pan(p.getPan()));
            x.put("canContact", !p.guarantor() || block == null);
            x.put("why", p.guarantor() ? (block == null ? "Borrower is " + l.getDpd() + " DPD" + (broken > 0 ? " with " + broken + " broken promise(s)" : "") + ": the guarantee may be invoked" : block)
                    : "Jointly and severally liable for the whole loan");
            return x;
        }).toList());
        return m;
    }

    @Transactional
    public CollectionContact log(Long loanId, Contact r, CurrentUser by) {
        LiveLoan l = book.get(loanId, by);
        if (r.channel() == null || !CHANNELS.contains(r.channel())) throw ApiException.unprocessable("BAD_CHANNEL", "Channel must be one of " + CHANNELS);
        if (r.outcome() == null || !OUTCOMES.contains(r.outcome())) throw ApiException.unprocessable("BAD_OUTCOME", "Outcome must be one of " + OUTCOMES);
        Instant at;
        try { at = r.contactedAt() == null || r.contactedAt().isBlank() ? Instant.now() : OffsetDateTime.parse(r.contactedAt()).toInstant(); }
        catch (Exception e) { throw ApiException.unprocessable("BAD_TIME", "contactedAt must be an ISO date-time with offset"); }
        if (at.isAfter(Instant.now().plusSeconds(300))) throw ApiException.unprocessable("BAD_TIME", "A contact cannot be in the future");
        if (Set.of("CALL", "FIELD_VISIT").contains(r.channel()) && !CollectionsAi.withinContactHours(at.atZone(IST).toLocalTime()))
            throw ApiException.unprocessable("CONTACT_HOURS", "Borrowers may be called or visited only between 8 am and 7 pm (RBI fair practices code)");
        CollectionContact c = new CollectionContact();
        if (r.partyId() != null) {
            var p = book.parties(loanId).stream().filter(x -> x.getId().equals(r.partyId())).findFirst()
                    .orElseThrow(() -> ApiException.unprocessable("BAD_PARTY", "That person is not on this loan"));
            if (p.guarantor()) {
                List<CollectionContact> prior = new ArrayList<>(contacts.findByLoanIdOrderByIdDesc(loanId));
                refreshPromises(l, prior);
                long broken = prior.stream().filter(x -> "BROKEN".equals(x.getPtpStatus())).count();
                String block = guarantorBlock(l.getDpd(), broken);
                if (block != null) throw ApiException.conflict("GUARANTOR_TOO_EARLY", block);
            }
            c.setPartyId(p.getId());
            c.setContactName(p.getName() + " (" + ("GUARANTOR".equals(p.getRole()) ? "guarantor" : "co-applicant") + ")");
        }
        c.setLoanId(loanId);
        c.setChannel(r.channel());
        c.setOutcome(r.outcome());
        c.setContactedAt(at);
        c.setDpdAtContact(l.getDpd());
        c.setNote(r.note() == null ? null : r.note().length() > 500 ? r.note().substring(0, 500) : r.note());
        c.setCreatedBy(by.username());
        if ("PROMISE_TO_PAY".equals(r.outcome())) {
            LocalDate d;
            try { d = LocalDate.parse(r.ptpDate()); } catch (Exception e) { throw ApiException.unprocessable("PTP_DATE", "A promise to pay needs the promised date (YYYY-MM-DD)"); }
            LocalDate made = at.atZone(IST).toLocalDate();
            if (d.isBefore(made) || d.isAfter(made.plusDays(30))) throw ApiException.unprocessable("PTP_DATE", "The promised date must be within 30 days");
            if (r.ptpAmount() == null || r.ptpAmount() <= 0) throw ApiException.unprocessable("PTP_AMOUNT", "A promise to pay needs the amount");
            // a new promise replaces an open one
            contacts.findByLoanIdOrderByIdDesc(loanId).stream().filter(x -> "OPEN".equals(x.getPtpStatus())).forEach(x -> { x.setPtpStatus("REPLACED"); contacts.save(x); });
            c.setPtpDate(d);
            c.setPtpAmount(BigDecimal.valueOf(r.ptpAmount()).setScale(2, RoundingMode.HALF_UP));
            c.setPtpStatus("OPEN");
        }
        contacts.save(c);
        audit.record(by, "collections.contact", "LOAN", l.getLoanRef(), l.getApplicationId(), (c.getContactName() == null ? "" : c.getContactName() + " · ") + r.channel() + " · " + r.outcome()
                + (c.getPtpDate() == null ? "" : " · promised ₹" + c.getPtpAmount() + " by " + c.getPtpDate()));
        return c;
    }

    public Map<String, Object> stats(CurrentUser u) {
        List<CollectionContact> all = contacts.findAll();
        Map<String, Long> byOutcome = all.stream().collect(Collectors.groupingBy(CollectionContact::getOutcome, TreeMap::new, Collectors.counting()));
        long kept = all.stream().filter(c -> "KEPT".equals(c.getPtpStatus())).count(), broken = all.stream().filter(c -> "BROKEN".equals(c.getPtpStatus())).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contacts", all.size());
        m.put("byOutcome", byOutcome);
        m.put("promisesKept", kept);
        m.put("promisesBroken", broken);
        m.put("promisesOpen", all.stream().filter(c -> "OPEN".equals(c.getPtpStatus())).count());
        m.put("keptRate", kept + broken == 0 ? null : Math.round(1000.0 * kept / (kept + broken)) / 1000.0);
        return m;
    }
}
