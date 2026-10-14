package com.rhythm.los.growth;

import com.rhythm.los.application.ApplicationRequest;
import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.application.LoanApplicationRepository;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.book.LiveLoan;
import com.rhythm.los.book.LoanBookService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Top-up offers for good payers. An offer is valid for 30 days; taking it up creates a draft application pre-filled
 * from the original one (same customer, bank account and address) for the offered amount. Consents are not copied:
 * the borrower gives fresh bureau, Account Aggregator and KYC consent, and the application is underwritten afresh.
 */
@Service
public class TopupService {
    private final TopupOfferRepository offers;
    private final LoanBookService book;
    private final LoanApplicationRepository apps;
    private final ApplicationService applications;
    private final PolicyService policy;
    private final AuditService audit;

    public TopupService(TopupOfferRepository offers, LoanBookService book, LoanApplicationRepository apps, ApplicationService applications,
                        PolicyService policy, AuditService audit) {
        this.offers = offers;
        this.book = book;
        this.apps = apps;
        this.applications = applications;
        this.policy = policy;
        this.audit = audit;
    }

    TopupRules.Check check(LiveLoan l) {
        return TopupRules.check(l, l.monthsOnBook(LoanBookService.asOf(l)), policy.live().exposureCap());
    }

    @Transactional
    public List<Map<String, Object>> eligible(CurrentUser u) {
        expire();
        Map<Long, TopupOffer> open = new HashMap<>();
        for (TopupOffer o : offers.findByStatusOrderByIdDesc("OFFERED")) open.putIfAbsent(o.getLoanId(), o);
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveLoan l : book.visible(u)) {
            TopupRules.Check c = check(l);
            if (!c.eligible()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("loanId", l.getId());
            m.put("loanRef", l.getLoanRef());
            m.put("borrowerName", l.getBorrowerName());
            m.put("segment", l.getSegment());
            m.put("productCode", l.getProductCode());
            m.put("outstanding", l.getOutstanding());
            m.put("paidEmis", l.getPaidEmis());
            m.put("behaviourPd", l.getBehaviourPd());
            m.put("amount", c.amount());
            m.put("rate", c.rate());
            m.put("tenureMonths", c.tenureMonths());
            m.put("canApply", l.getApplicationId() != null);
            m.put("offer", open.get(l.getId()));
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> ((Number) m.get("amount")).doubleValue()).reversed());
        return out;
    }

    public Map<String, Object> checkLoan(Long loanId, CurrentUser u) {
        LiveLoan l = book.get(loanId, u);
        TopupRules.Check c = check(l);
        return Map.of("eligible", c.eligible(), "reasons", c.reasons(), "amount", c.amount(), "rate", c.rate(), "tenureMonths", c.tenureMonths(),
                "offers", offers.findByLoanIdOrderByIdDesc(loanId));
    }

    void expire() {
        for (TopupOffer o : offers.findByStatusOrderByIdDesc("OFFERED"))
            if (o.getExpiresOn().isBefore(LocalDate.now())) { o.setStatus("EXPIRED"); offers.save(o); }
    }

    @Transactional
    public TopupOffer offer(Long loanId, CurrentUser by) {
        LiveLoan l = book.get(loanId, by);
        expire();
        if (offers.findByLoanIdOrderByIdDesc(loanId).stream().anyMatch(o -> "OFFERED".equals(o.getStatus())))
            throw ApiException.conflict("OFFER_OPEN", "This borrower already has an open top-up offer");
        TopupRules.Check c = check(l);
        if (!c.eligible()) throw ApiException.unprocessable("NOT_ELIGIBLE", "Not eligible for a top-up: " + String.join("; ", c.reasons()));
        TopupOffer o = new TopupOffer();
        o.setLoanId(loanId);
        o.setAmount(BigDecimal.valueOf(c.amount()).setScale(2, RoundingMode.HALF_UP));
        o.setRate(BigDecimal.valueOf(c.rate()).setScale(2, RoundingMode.HALF_UP));
        o.setTenureMonths(c.tenureMonths());
        o.setExpiresOn(LocalDate.now().plusDays(30));
        o.setOfferedBy(by.username());
        offers.save(o);
        audit.record(by, "topup.offered", "LOAN", l.getLoanRef(), l.getApplicationId(), "₹" + o.getAmount() + " at " + o.getRate() + "% for " + o.getTenureMonths() + " months");
        return o;
    }

    @Transactional
    public Map<String, Object> decline(Long offerId, CurrentUser by) {
        TopupOffer o = offers.findById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        LiveLoan l = book.get(o.getLoanId(), by);
        if (!"OFFERED".equals(o.getStatus())) throw ApiException.conflict("OFFER_CLOSED", "The offer is " + o.getStatus().toLowerCase());
        o.setStatus("DECLINED");
        offers.save(o);
        audit.record(by, "topup.declined", "LOAN", l.getLoanRef(), l.getApplicationId(), null);
        return Map.of("offer", o);
    }

    /** The borrower takes up the offer: a draft application pre-filled from the original. */
    @Transactional
    public Map<String, Object> apply(Long offerId, CurrentUser by) {
        TopupOffer o = offers.findById(offerId).orElseThrow(() -> ApiException.notFound("Offer"));
        LiveLoan l = book.get(o.getLoanId(), by);
        if (!"OFFERED".equals(o.getStatus())) throw ApiException.conflict("OFFER_CLOSED", "The offer is " + o.getStatus().toLowerCase());
        if (o.getExpiresOn().isBefore(LocalDate.now())) throw ApiException.conflict("OFFER_EXPIRED", "The offer expired on " + o.getExpiresOn());
        if (l.getApplicationId() == null) throw ApiException.conflict("NO_ORIGINAL", "This loan came from the LMS feed with no application in Rhythm: start a new application");
        LoanApplication a = apps.findById(l.getApplicationId()).orElseThrow(() -> ApiException.notFound("Application"));
        ApplicationRequest r = new ApplicationRequest(a.getProductCode(), a.getApplicantName(), a.getPan(), a.getMobile(), a.getEmail(), a.getDob(), a.getGender(),
                a.getAddress(), a.getCity(), a.getPincode(), a.getBusinessName(), a.getBusinessVintageYears(), a.getDeclaredMonthlyIncome(), a.getEssentialExpenses(),
                o.getAmount(), o.getTenureMonths(), "Top-up on loan " + l.getLoanRef(), a.getBankAccountNo(), a.getBankIfsc(), false, false, false,
                a.getPreferredLanguage(), a.getIndustry(), a.getAddressLat(), a.getAddressLng());
        LoanApplication n = applications.create(r, by);
        o.setStatus("APPLIED");
        o.setApplicationId(n.getId());
        offers.save(o);
        audit.record(by, "topup.applied", "LOAN", l.getLoanRef(), n.getId(), "Draft " + n.getAppNo() + " for ₹" + o.getAmount());
        return Map.of("offer", o, "applicationId", n.getId(), "appNo", n.getAppNo());
    }
}
