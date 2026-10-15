package com.rhythm.los.tenant;

import com.rhythm.los.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Platform tenants, tenant onboarding (SCR-094) and benchmarking. */
@RestController
@RequestMapping("/api")
public class TenantController {
    private final TenantService tenants;
    private final BenchmarkService benchmarks;
    private CostService costs;

    @org.springframework.beans.factory.annotation.Autowired
    void setCosts(CostService c) { this.costs = c; }
    private final TenantRegistry registry;

    public TenantController(TenantService tenants, BenchmarkService benchmarks, TenantRegistry registry) {
        this.tenants = tenants;
        this.benchmarks = benchmarks;
        this.registry = registry;
    }

    /** Which organisation this session belongs to (shown in the header). */
    @GetMapping("/tenant")
    public Map<String, Object> current() {
        var e = registry.find(TenantContext.code()).orElseThrow();
        return Map.of("code", e.code(), "name", e.name(), "status", e.status(), "isDefault", TenantContext.isDefault());
    }

    @GetMapping("/platform/tenants")
    @PreAuthorize("hasRole('ADMIN')")
    public List<Map<String, Object>> list() { return tenants.list(CurrentUser.get()); }

    @PostMapping("/platform/tenants")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> create(@RequestBody TenantService.Create r) { return tenants.create(r, CurrentUser.get()); }

    @PostMapping("/platform/tenants/{code}/suspend")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> suspend(@PathVariable String code) { return tenants.setStatus(code, "SUSPENDED", CurrentUser.get()); }

    @PostMapping("/platform/tenants/{code}/reactivate")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reactivate(@PathVariable String code) { return tenants.setStatus(code, "ACTIVE", CurrentUser.get()); }

    @GetMapping("/onboarding")
    @PreAuthorize("hasAnyRole('ADMIN','CRO','COMPLIANCE')")
    public Map<String, Object> onboarding() { return tenants.status(CurrentUser.get()); }

    @PostMapping("/onboarding/steps/{step}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> confirm(@PathVariable int step) { return tenants.confirm(step, CurrentUser.get()); }

    @PostMapping("/onboarding/sandbox")
    @PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public Map<String, Object> sandbox() { return tenants.runSandbox(CurrentUser.get()); }

    @PostMapping("/onboarding/go-live/request")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> requestGoLive(@RequestBody TenantService.GoLive r) { return tenants.requestGoLive(r, CurrentUser.get()); }

    @PostMapping("/onboarding/go-live/approve")
    @PreAuthorize("hasRole('CRO')")
    public Map<String, Object> approveGoLive() { return tenants.approveGoLive(CurrentUser.get()); }

    public record OptIn(boolean optIn) {}

    @PostMapping("/onboarding/benchmarking")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> optIn(@RequestBody OptIn r) { return tenants.optIn(r.optIn(), CurrentUser.get()); }

    @GetMapping("/benchmarking")
    @PreAuthorize("hasAnyRole('ADMIN','CRO','CREDIT_MANAGER','COMPLIANCE')")
    public Map<String, Object> benchmarking() { return benchmarks.view(CurrentUser.get()); }

    @GetMapping("/benchmarking/cost")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CRO','CREDIT_MANAGER','COMPLIANCE','OPERATIONS')")
    public Map<String, Object> cost(@RequestParam(defaultValue = "180") int days) { return costs.perFile(days); }

    @GetMapping("/benchmarking/cost-rates")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CRO','CREDIT_MANAGER','COMPLIANCE','OPERATIONS')")
    public java.util.List<Map<String, Object>> costRates() { return costs.rates(); }

    public record Rates(java.util.List<CostService.RateChange> rates) {}

    @PutMapping("/benchmarking/cost-rates")
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public java.util.List<Map<String, Object>> setCostRates(@RequestBody Rates r) { return costs.updateRates(r.rates(), CurrentUser.get()); }

    @PostMapping("/benchmarking/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> refresh() { return benchmarks.refresh(CurrentUser.get()); }
}
