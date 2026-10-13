package com.rhythm.los.model;

import com.rhythm.los.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/models")
@PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO','ADMIN','COMPLIANCE')")
public class ModelController {
    private final ModelService models;

    public ModelController(ModelService models) { this.models = models; }

    @GetMapping
    public List<ModelVersion> list() { return models.list(); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) { return models.detail(id); }

    @PostMapping("/train")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO','ADMIN')")
    public Map<String, Object> train() { return models.train(CurrentUser.get()); }

    @PostMapping("/{id}/decision")
    @PreAuthorize("hasRole('CRO')")
    public ModelVersion decide(@PathVariable Long id, @RequestBody ModelService.Decision r) { return models.decide(id, r, CurrentUser.get()); }

    public record Note(String note) {}

    @PostMapping("/champion/retire")
    @PreAuthorize("hasRole('CRO')")
    public ResponseEntity<Void> retire(@RequestBody Note r) {
        models.retireChampion(r.note(), CurrentUser.get());
        return ResponseEntity.noContent().build();
    }
}
