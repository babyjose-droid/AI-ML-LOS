package com.rhythm.los.growth;

import com.rhythm.los.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Restructuring, collections, top-up offers and the rate simulator. */
@RestController
@RequestMapping("/api")
public class GrowthController {
    static final String BOOK = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','COMPLIANCE')";
    static final String MAKER = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER')";
    static final String CHECKER = "hasAnyRole('ADMIN','CREDIT_MANAGER','CRO')";
    static final String COLLECT = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FIELD_OFFICER')";
    static final String SELL = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_MANAGER','CRO')";
    static final String PRICE = "hasAnyRole('ADMIN','CREDIT_MANAGER','CRO')";

    private final RestructureService restructure;
    private final CollectionsService collections;
    private final TopupService topups;
    private final RateSimService rates;

    public GrowthController(RestructureService restructure, CollectionsService collections, TopupService topups, RateSimService rates) {
        this.restructure = restructure;
        this.collections = collections;
        this.topups = topups;
        this.rates = rates;
    }

    public record Decision(String note) {}

    // restructuring
    @PostMapping("/book/loans/{id}/restructure")
    @PreAuthorize(MAKER)
    public LoanRestructure propose(@PathVariable Long id, @RequestBody RestructureService.Request r) { return restructure.request(id, r, CurrentUser.get()); }

    @GetMapping("/book/loans/{id}/restructures")
    @PreAuthorize(BOOK)
    public List<LoanRestructure> restructures(@PathVariable Long id) { return restructure.forLoan(id, CurrentUser.get()); }

    @GetMapping("/restructures/pending")
    @PreAuthorize(BOOK)
    public List<Map<String, Object>> pending() { return restructure.pending(CurrentUser.get()); }

    @PostMapping("/restructures/{id}/approve")
    @PreAuthorize(CHECKER)
    public LoanRestructure approve(@PathVariable Long id, @RequestBody(required = false) Decision d) { return restructure.decide(id, true, d == null ? null : d.note(), CurrentUser.get()); }

    @PostMapping("/restructures/{id}/reject")
    @PreAuthorize(CHECKER)
    public LoanRestructure reject(@PathVariable Long id, @RequestBody(required = false) Decision d) { return restructure.decide(id, false, d == null ? null : d.note(), CurrentUser.get()); }

    // collections
    @GetMapping("/collections/queue")
    @PreAuthorize(COLLECT)
    public List<Map<String, Object>> queue() { return collections.queue(CurrentUser.get()); }

    @GetMapping("/collections/stats")
    @PreAuthorize(COLLECT)
    public Map<String, Object> stats() { return collections.stats(CurrentUser.get()); }

    @GetMapping("/collections/loans/{id}")
    @PreAuthorize(COLLECT)
    public Map<String, Object> contacts(@PathVariable Long id) { return collections.forLoan(id, CurrentUser.get()); }

    @PostMapping("/collections/loans/{id}/contacts")
    @PreAuthorize(COLLECT)
    public CollectionContact log(@PathVariable Long id, @RequestBody CollectionsService.Contact c) { return collections.log(id, c, CurrentUser.get()); }

    // top-ups
    @GetMapping("/topups/eligible")
    @PreAuthorize(SELL)
    public List<Map<String, Object>> eligible() { return topups.eligible(CurrentUser.get()); }

    @GetMapping("/topups/loans/{id}")
    @PreAuthorize(SELL)
    public Map<String, Object> checkLoan(@PathVariable Long id) { return topups.checkLoan(id, CurrentUser.get()); }

    @PostMapping("/topups/loans/{id}/offer")
    @PreAuthorize(SELL)
    public TopupOffer offer(@PathVariable Long id) { return topups.offer(id, CurrentUser.get()); }

    @PostMapping("/topups/offers/{id}/apply")
    @PreAuthorize(SELL)
    public Map<String, Object> apply(@PathVariable Long id) { return topups.apply(id, CurrentUser.get()); }

    @PostMapping("/topups/offers/{id}/decline")
    @PreAuthorize(SELL)
    public Map<String, Object> decline(@PathVariable Long id) { return topups.decline(id, CurrentUser.get()); }

    // pricing
    @PostMapping("/pricing/simulate")
    @PreAuthorize(PRICE)
    public Map<String, Object> simulate(@RequestBody RateSimService.Request r) { return rates.simulate(r, CurrentUser.get()); }
}
