package com.rhythm.los.scale;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Co-lending matcher and capital planning. */
@RestController
@RequestMapping("/api")
public class ScaleController {
    static final String CL_VIEW = "hasAnyRole('ADMIN','CRO','CREDIT_MANAGER','OPERATIONS','COMPLIANCE')";
    static final String CL_ACT = "hasAnyRole('ADMIN','CRO','OPERATIONS')";
    static final String CAPITAL = "hasAnyRole('ADMIN','CRO','COMPLIANCE')";

    private final CoLendingService colending;
    private final CapitalService capital;

    public ScaleController(CoLendingService colending, CapitalService capital) {
        this.colending = colending;
        this.capital = capital;
    }

    @GetMapping("/colending/partners")
    @PreAuthorize(CL_VIEW)
    public List<CoLendingPartner> partners() { return colending.partners(); }

    @PutMapping("/colending/partners/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public CoLendingPartner updatePartner(@PathVariable Long id, @RequestBody Map<String, Object> r) { return colending.updatePartner(id, r, CurrentUser.get()); }

    @GetMapping("/colending/pool")
    @PreAuthorize(CL_VIEW)
    public Map<String, Object> pool() { return colending.pool(CurrentUser.get()); }

    @PostMapping("/colending/submit")
    @PreAuthorize(CL_ACT)
    public Map<String, Object> submit(@RequestBody CoLendingService.Submit r) { return colending.submit(r, CurrentUser.get()); }

    @GetMapping(value = "/colending/batches/{batch}/file", produces = "text/csv")
    @PreAuthorize(CL_ACT)
    public ResponseEntity<String> file(@PathVariable String batch) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + batch + ".csv")
                .contentType(MediaType.parseMediaType("text/csv")).body(colending.file(batch, CurrentUser.get()));
    }

    @GetMapping("/colending/assignments")
    @PreAuthorize(CL_VIEW)
    public List<Map<String, Object>> assignments() { return colending.assignments(CurrentUser.get()); }

    @PostMapping("/colending/assignments/{id}/decision")
    @PreAuthorize(CL_ACT)
    public CoLendingAssignment decide(@PathVariable Long id, @RequestBody CoLendingService.Decide r) { return colending.decide(id, r, CurrentUser.get()); }

    @GetMapping("/capital/defaults")
    @PreAuthorize(CAPITAL)
    public Map<String, Object> defaults() { return capital.defaults(CurrentUser.get()); }

    @PostMapping("/capital/project")
    @PreAuthorize(CAPITAL)
    public Map<String, Object> project(@RequestBody CapitalPlanner.Inputs in) { return capital.project(in); }

    @PostMapping("/capital/scenarios")
    @PreAuthorize("hasAnyRole('ADMIN','CRO')")
    public CapitalScenario save(@RequestBody CapitalService.Save r) { return capital.save(r, CurrentUser.get()); }

    @GetMapping("/capital/scenarios")
    @PreAuthorize(CAPITAL)
    public List<CapitalScenario> scenarios() { return capital.list(); }
}
