package com.rhythm.los.flow;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Workflow designer (per product) and the stages of each application. */
@RestController
public class WorkflowController {
    private final WorkflowDesignService design;
    private final WorkflowService flow;

    public WorkflowController(WorkflowDesignService design, WorkflowService flow) {
        this.design = design;
        this.flow = flow;
    }

    @GetMapping("/api/workflows")
    public List<Map<String, Object>> overview() { return design.overview(); }

    @GetMapping("/api/workflows/library")
    public Map<String, Object> library() {
        return Map.of("stages", design.library(), "conditions", design.conditionFields());
    }

    @GetMapping("/api/workflows/{id}")
    public Map<String, Object> detail(@PathVariable Long id) { return design.detail(id); }

    public record DraftRequest(String productCode) {}

    @PostMapping("/api/workflows/drafts")
    @PreAuthorize("hasAnyRole('ADMIN','CREDIT_MANAGER')")
    public Map<String, Object> draft(@RequestBody DraftRequest r) {
        return design.detail(design.createDraft(r.productCode(), CurrentUser.get()).getId());
    }

    @PutMapping("/api/workflows/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CREDIT_MANAGER')")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody WorkflowDesignService.UpdateRequest r) {
        return design.update(id, r, CurrentUser.get());
    }

    @PostMapping("/api/workflows/{id}/submit")
    @PreAuthorize("hasAnyRole('ADMIN','CREDIT_MANAGER')")
    public Map<String, Object> submit(@PathVariable Long id) { return design.submit(id, CurrentUser.get()); }

    @PostMapping("/api/workflows/{id}/decision")
    @PreAuthorize("hasRole('CRO')")
    public Map<String, Object> decide(@PathVariable Long id, @RequestBody WorkflowDesignService.Decision r) {
        return design.decide(id, r, CurrentUser.get());
    }

    @DeleteMapping("/api/workflows/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CREDIT_MANAGER')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        design.deleteDraft(id, CurrentUser.get());
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------- application stages

    @GetMapping("/api/applications/{id}/stages")
    public Map<String, Object> stages(@PathVariable Long id) { return flow.view(id, CurrentUser.get()); }

    @PostMapping("/api/applications/{id}/stages/{key}/complete")
    public Map<String, Object> complete(@PathVariable Long id, @PathVariable String key, @RequestBody WorkflowService.CompleteRequest r) {
        return flow.complete(id, key, r, CurrentUser.get());
    }

    public record Note(String note) {}

    @PostMapping("/api/applications/{id}/stages/{key}/review")
    public Map<String, Object> review(@PathVariable Long id, @PathVariable String key, @RequestBody Note r) {
        return flow.review(id, key, r.note(), CurrentUser.get());
    }
}
