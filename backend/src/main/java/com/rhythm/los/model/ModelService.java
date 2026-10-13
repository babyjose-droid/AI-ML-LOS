package com.rhythm.los.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.Features;
import com.rhythm.los.decision.engine.PdModel;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.org.Role;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import com.rhythm.los.portfolio.HistoricalLoan;
import com.rhythm.los.portfolio.HistoricalLoanRepository;
import com.rhythm.los.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Model lifecycle: train a candidate PD model on the loan book, compare it with the model in use on hold-out loans,
 * and let the CRO (never the person who trained it) make it the champion. Until a champion is approved the engine
 * uses its built-in illustrative coefficients.
 */
@Service
public class ModelService {
    public static final String NAME = "RHY-PD-TRAINED";
    static final Set<Role> TRAINERS = EnumSet.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);

    private final ModelVersionRepository repo;
    private final HistoricalLoanRepository book;
    private final PolicyService policy;
    private final ModelTrainer trainer;
    private final AuditService audit;
    private volatile Optional<PdModel> champion;

    public ModelService(ModelVersionRepository repo, HistoricalLoanRepository book, PolicyService policy, ModelTrainer trainer, AuditService audit) {
        this.repo = repo;
        this.book = book;
        this.policy = policy;
        this.trainer = trainer;
        this.audit = audit;
    }

    /** The approved model the engine uses, or empty for the built-in coefficients. */
    public Optional<PdModel> champion() {
        Optional<PdModel> c = champion;
        if (c == null) {
            c = repo.findFirstByStatusOrderByIdDesc("CHAMPION").map(ModelService::toModel);
            champion = c;
        }
        return c;
    }

    static PdModel toModel(ModelVersion v) {
        List<Map<String, Object>> t = Json.read(v.getTermsJson(), new TypeReference<List<Map<String, Object>>>() {});
        List<PdModel.Term> terms = t.stream().map(x -> new PdModel.Term((String) x.get("feature"),
                ((Number) x.get("weight")).doubleValue(), ((Number) x.get("mean")).doubleValue())).toList();
        return new PdModel(v.getName(), v.getVersion(), v.getIntercept(), terms);
    }

    public List<ModelVersion> list() { return repo.findAllByOrderByIdDesc(); }

    public ModelVersion get(Long id) { return repo.findById(id).orElseThrow(() -> ApiException.notFound("Model")); }

    public Map<String, Object> detail(Long id) {
        ModelVersion v = get(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", v);
        List<Map<String, Object>> terms = Json.read(v.getTermsJson(), new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> t : terms) t.put("label", Features.def((String) t.get("feature")).label());
        m.put("terms", terms);
        m.put("metrics", Json.read(v.getMetricsJson(), Map.class));
        return m;
    }

    /** Builds the training set from the loan book and asks the trainer for a candidate. */
    @Transactional
    public ModelVersion train(CurrentUser by) {
        if (!TRAINERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Only credit managers and the CRO can train models");
        List<HistoricalLoan> loans = book.findAllByOrderByIdAsc();
        if (loans.size() < 200) throw ApiException.unprocessable("NOT_ENOUGH_DATA", "At least 200 past loans with outcomes are needed (" + loans.size() + " available)");
        PolicyParams pol = policy.live();
        PdModel current = champion().orElse(null);
        List<Map<String, Object>> rows = new ArrayList<>(loans.size());
        Set<String> sources = new TreeSet<>();
        for (HistoricalLoan h : loans) {
            EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
            EngineResult r = UnderwritingEngine.underwrite(in, pol, current);
            rows.add(Map.of("features", Features.of(in), "bad", h.isOutcomeBad(), "championPd", r.pd()));
            sources.add(h.getSource());
        }
        List<String> feats = Features.ALL.stream().map(Features.Def::key).toList();
        Map<String, Object> out = trainer.train(Map.of("features", feats, "rows", rows, "l2", 1.0));

        ModelVersion v = new ModelVersion();
        v.setName(NAME);
        v.setVersion("1." + (repo.countByName(NAME) + 1));
        v.setStatus("CANDIDATE");
        v.setIntercept(((Number) out.get("intercept")).doubleValue());
        v.setTermsJson(Json.write(out.get("terms")));
        Map<String, Object> metrics = new LinkedHashMap<>(out);
        metrics.remove("terms");
        metrics.remove("intercept");
        metrics.put("championName", current == null ? UnderwritingEngine.MODEL_THICK + " / " + UnderwritingEngine.MODEL_NTC + " (built-in)" : current.label());
        v.setMetricsJson(Json.write(metrics));
        v.setDataSource(sources.size() > 1 ? "MIXED" : sources.isEmpty() ? "NONE" : sources.iterator().next());
        v.setRowsUsed(loans.size());
        v.setTrainedBy(by.username());
        repo.save(v);
        Object hold = metrics.get("holdout");
        audit.record(by, "model.trained", "MODEL", v.getName() + " " + v.getVersion(), null,
                loans.size() + " loans (" + v.getDataSource() + ") · hold-out " + (hold instanceof Map<?, ?> h ? "AUC " + h.get("auc") : "") +
                        (metrics.get("aucGain") != null ? " · gain over champion " + metrics.get("aucGain") : ""));
        return v;
    }

    public record Decision(boolean approve, String note) {}

    @Transactional
    public ModelVersion decide(Long id, Decision req, CurrentUser by) {
        ModelVersion v = get(id);
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can approve a model");
        if (!"CANDIDATE".equals(v.getStatus())) throw ApiException.conflict("NOT_CANDIDATE", "Model is " + v.getStatus());
        if (by.username().equals(v.getTrainedBy())) throw ApiException.forbidden("MAKER_CHECKER", "The person who trained a model cannot approve it");
        String note = req.note() == null ? "" : req.note().trim();
        if (note.length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        v.setDecidedBy(by.username());
        v.setDecidedAt(Instant.now());
        v.setDecisionNote(note.length() > 1000 ? note.substring(0, 1000) : note);
        if (req.approve()) {
            repo.findFirstByStatusOrderByIdDesc("CHAMPION").ifPresent(old -> { old.setStatus("RETIRED"); repo.save(old); });
            v.setStatus("CHAMPION");
            audit.record(by, "model.approved_champion", "MODEL", v.getName() + " " + v.getVersion(), null, note);
        } else {
            v.setStatus("REJECTED");
            audit.record(by, "model.rejected", "MODEL", v.getName() + " " + v.getVersion(), null, note);
        }
        repo.save(v);
        champion = null; // reload on next use
        return v;
    }

    /** Back to the built-in model (for example if the champion misbehaves in production). */
    @Transactional
    public void retireChampion(String note, CurrentUser by) {
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can retire the champion model");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        ModelVersion c = repo.findFirstByStatusOrderByIdDesc("CHAMPION").orElseThrow(() -> ApiException.conflict("NO_CHAMPION", "No trained model is in use"));
        c.setStatus("RETIRED");
        c.setDecisionNote(note.trim());
        repo.save(c);
        champion = null;
        audit.record(by, "model.champion_retired", "MODEL", c.getName() + " " + c.getVersion(), null, note.trim());
    }
}
