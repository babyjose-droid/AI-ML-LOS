package com.rhythm.los.risk;

import com.rhythm.los.application.ApplicationService;
import com.rhythm.los.security.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Network risk, household cash flow and industry scorecards. */
@RestController
public class RiskController {
    static final String STAFF = "hasAnyRole('ADMIN','SALES','OPERATIONS','CREDIT_OFFICER','CREDIT_MANAGER','CRO','FRAUD_ANALYST','COMPLIANCE','FIELD_OFFICER')";

    private final NetworkService network;
    private final HouseholdService households;
    private final ScorecardService scorecards;
    private final ApplicationService apps;
    @org.springframework.beans.factory.annotation.Autowired private FairnessService fairness;

    @GetMapping("/api/fairness")
    @PreAuthorize("hasAnyRole('CRO','CREDIT_MANAGER','COMPLIANCE','ADMIN')")
    public Map<String, Object> fairness(@RequestParam(defaultValue = "90") int days) { return fairness.report(days); }

    public RiskController(NetworkService network, HouseholdService households, ScorecardService scorecards, ApplicationService apps) {
        this.network = network;
        this.households = households;
        this.scorecards = scorecards;
        this.apps = apps;
    }

    @GetMapping("/api/applications/{id}/network")
    @PreAuthorize(STAFF)
    public NetworkService.Network network(@PathVariable Long id) { return network.view(apps.get(id), CurrentUser.get()); }

    @GetMapping("/api/applications/{id}/household")
    @PreAuthorize(STAFF)
    public Map<String, Object> household(@PathVariable Long id) { apps.get(id); return households.view(id); }

    @PutMapping("/api/applications/{id}/household")
    @PreAuthorize("hasAnyRole('SALES','OPERATIONS','FIELD_OFFICER','CREDIT_OFFICER','ADMIN')")
    public Map<String, Object> saveHousehold(@PathVariable Long id, @RequestBody HouseholdService.HouseholdIn in) {
        return households.save(id, in, CurrentUser.get());
    }

    @GetMapping("/api/scorecards")
    @PreAuthorize(STAFF)
    public List<Scorecard.Definition> scorecards() { return scorecards.all(); }

    @PutMapping("/api/admin/scorecards/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public Scorecard.Definition updateScorecard(@PathVariable String code, @RequestBody Scorecard.Definition d) {
        return scorecards.update(code, d, CurrentUser.get());
    }
}
