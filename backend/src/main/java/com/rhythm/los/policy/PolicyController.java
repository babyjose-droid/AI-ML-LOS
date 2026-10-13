package com.rhythm.los.policy;

import com.rhythm.los.common.Json;
import com.rhythm.los.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {
    static final String CREDIT = "hasAnyRole('CREDIT_MANAGER','CRO','ADMIN')";

    private final PolicyRepository repo;
    private final PolicyService service;
    private final PolicyStudioService studio;

    public PolicyController(PolicyRepository repo, PolicyService service, PolicyStudioService studio) {
        this.repo = repo;
        this.service = service;
        this.studio = studio;
    }

    @GetMapping("/live")
    public PolicyParams live() { return service.live(); }

    @GetMapping
    public List<PolicyVersion> all() { return repo.findAllByOrderByIdDesc(); }

    @GetMapping("/fields")
    public List<PolicyFields.Field> fields() { return PolicyFields.ALL; }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        PolicyVersion v = studio.get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policy", v);
        m.put("params", studio.params(v));
        m.put("changes", studio.changes(v));
        m.put("simulation", v.getSimulationJson() == null ? null : Json.read(v.getSimulationJson(), Map.class));
        return m;
    }

    public record DraftRequest(String notes) {}
    public record UpdateRequest(Map<String, Object> params, String notes) {}

    @PostMapping("/drafts")
    @PreAuthorize(CREDIT)
    public PolicyVersion draft(@RequestBody(required = false) DraftRequest r) {
        return studio.createDraft(r == null ? null : r.notes(), CurrentUser.get());
    }

    @PutMapping("/{id}")
    @PreAuthorize(CREDIT)
    public PolicyVersion update(@PathVariable Long id, @RequestBody UpdateRequest r) {
        return studio.update(id, r.params(), r.notes(), CurrentUser.get());
    }

    @PostMapping("/{id}/simulate")
    @PreAuthorize(CREDIT)
    public Map<String, Object> simulate(@PathVariable Long id) { return studio.simulate(id, CurrentUser.get()); }

    @PostMapping("/{id}/submit")
    @PreAuthorize(CREDIT)
    public PolicyVersion submit(@PathVariable Long id) { return studio.submit(id, CurrentUser.get()); }

    @PostMapping("/{id}/decision")
    @PreAuthorize(CREDIT)
    public PolicyVersion decide(@PathVariable Long id, @RequestBody PolicyStudioService.Decision r) { return studio.decide(id, r, CurrentUser.get()); }

    @DeleteMapping("/{id}")
    @PreAuthorize(CREDIT)
    public ResponseEntity<Void> discard(@PathVariable Long id) {
        studio.discard(id, CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
