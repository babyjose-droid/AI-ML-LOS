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
    private final com.rhythm.los.portfolio.HistoryImportService history;

    public ModelController(ModelService models, com.rhythm.los.portfolio.HistoryImportService history) {
        this.models = models;
        this.history = history;
    }

    @GetMapping
    public List<ModelVersion> list() { return models.list(); }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) { return models.detail(id); }

    @GetMapping("/{id}/monitoring")
    public java.util.Map<String, Object> monitoring(@PathVariable Long id) { return models.monitoring(id); }

    @GetMapping("/monitoring/built-in/{kind}")
    public java.util.Map<String, Object> builtIn(@PathVariable String kind) { return models.monitoringBuiltIn(kind); }

    public record TrainRequest(Boolean onlyOwn) {}

    @PostMapping("/train")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO','ADMIN')")
    public Map<String, Object> train(@RequestBody(required = false) TrainRequest r) { return models.train(CurrentUser.get(), r != null && Boolean.TRUE.equals(r.onlyOwn())); }

    @PostMapping("/train-fraud")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO','ADMIN')")
    public Map<String, Object> trainFraud(@RequestBody(required = false) TrainRequest r) { return models.trainFraud(CurrentUser.get(), r != null && Boolean.TRUE.equals(r.onlyOwn())); }

    public record HistoryRows(List<Map<String, Object>> rows) {}

    @PostMapping("/history")
    @PreAuthorize("hasAnyRole('CREDIT_MANAGER','CRO','ADMIN')")
    public com.rhythm.los.portfolio.HistoryImportService.Result importHistory(@RequestBody HistoryRows r) { return history.importRows(r.rows(), CurrentUser.get()); }

    @GetMapping("/history")
    public Map<String, Object> history() { return history.summary(); }

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
