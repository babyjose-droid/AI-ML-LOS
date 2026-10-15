package com.rhythm.los.collateral;

import com.rhythm.los.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Collateral on a live loan (release, substitution) and the comparable sales behind the automated valuation. */
@RestController
@RequestMapping("/api")
public class CollateralLifecycleController {
    static final String BOOK = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','COMPLIANCE')";
    static final String ACT = "hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO')";

    private final CollateralLifecycleService lifecycle;
    private final CollateralService service;

    public CollateralLifecycleController(CollateralLifecycleService lifecycle, CollateralService service) {
        this.lifecycle = lifecycle;
        this.service = service;
    }

    @GetMapping("/book/loans/{id}/collateral")
    @PreAuthorize(BOOK)
    public Map<String, Object> forLoan(@PathVariable Long id) { return lifecycle.forLoan(id, CurrentUser.get()); }

    @PostMapping("/book/loans/{id}/collateral/release")
    @PreAuthorize(ACT)
    public CollateralChange release(@PathVariable Long id, @RequestBody CollateralLifecycleService.ReleaseRequest r) { return lifecycle.requestRelease(id, r, CurrentUser.get()); }

    @PostMapping("/book/loans/{id}/collateral/{cid}/substitute")
    @PreAuthorize(ACT)
    public Collateral declareSubstitute(@PathVariable Long id, @PathVariable Long cid, @RequestBody CollateralService.Declare r) {
        return lifecycle.declareSubstitute(id, cid, r, CurrentUser.get());
    }

    @PostMapping("/book/loans/{id}/collateral/substitution")
    @PreAuthorize(ACT)
    public CollateralChange substitution(@PathVariable Long id, @RequestBody CollateralLifecycleService.SubstituteRequest r) {
        return lifecycle.requestSubstitution(id, r, CurrentUser.get());
    }

    @PostMapping("/collateral-changes/{id}/decision")
    @PreAuthorize(ACT)
    public CollateralChange decide(@PathVariable Long id, @RequestBody CollateralLifecycleService.Decide d) { return lifecycle.decide(id, d, CurrentUser.get()); }

    @PostMapping("/collateral-changes/{id}/complete")
    @PreAuthorize(ACT)
    public CollateralChange complete(@PathVariable Long id, @RequestBody CollateralLifecycleService.Complete r) { return lifecycle.complete(id, r, CurrentUser.get()); }

    @GetMapping("/collateral/comparables")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','VALUER','LEGAL_REVIEWER')")
    public List<PropertySale> comparables() { return service.comparables(); }

    public record Import(String source, List<CollateralService.SaleRow> rows) {}

    @PostMapping("/collateral/comparables")
    @PreAuthorize("hasAnyRole('ADMIN','VALUER','CREDIT_MANAGER')")
    public Map<String, Object> importSales(@RequestBody Import r) { return service.importSales(r.rows(), r.source(), CurrentUser.get()); }
}
