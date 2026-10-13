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
    /** Separate calibrated models for applicants with and without a bureau record (BR-11, FSD 14.4). */
    public static final String NAME_THICK = "RHY-PD-THICK-TRAINED";
    public static final String NAME_NTC = "RHY-PD-NTC-TRAINED";
    static final Set<Role> TRAINERS = EnumSet.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    static final int MIN_ROWS = 200, MIN_BAD = 20;
    /** Bureau features have no meaning for a new-to-credit applicant, so the NTC model does not use them. */
    static final Set<String> BUREAU_FEATURES = Set.of("thinFile", "bureauScoreGap", "dpd12", "ever90", "enquiries6m", "historyYears");

    private final ModelVersionRepository repo;
    private final HistoricalLoanRepository book;
    private final PolicyService policy;
    private final ModelTrainer trainer;
    private final AuditService audit;
    private volatile Map<String, Optional<PdModel>> champions;

    public ModelService(ModelVersionRepository repo, HistoricalLoanRepository book, PolicyService policy, ModelTrainer trainer, AuditService audit) {
        this.repo = repo;
        this.book = book;
        this.policy = policy;
        this.trainer = trainer;
        this.audit = audit;
    }

    /** The approved model for applicants with a bureau record (thick) or without one (new to credit); empty = built-in. */
    public Optional<PdModel> champion(boolean thick) {
        Map<String, Optional<PdModel>> c = champions;
        if (c == null) {
            c = new HashMap<>();
            for (String k : List.of("THICK", "NTC"))
                c.put(k, repo.findFirstByKindAndStatusOrderByIdDesc(k, "CHAMPION").map(ModelService::toModel));
            champions = c;
        }
        return c.get(thick ? "THICK" : "NTC");
    }

    /** The model the engine uses for this applicant. */
    public PdModel modelFor(EngineInput in) { return champion(in.bureau() != null).orElse(null); }

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

    /**
     * Builds the training sets from the loan book, one for applicants with a bureau record and one without, and asks the
     * trainer for a candidate of each. A set too small to train on is skipped and reported.
     */
    @Transactional
    public Map<String, Object> train(CurrentUser by) {
        if (!TRAINERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Only credit managers and the CRO can train models");
        List<HistoricalLoan> loans = book.findAllByOrderByIdAsc();
        PolicyParams pol = policy.live();
        Map<String, List<Map<String, Object>>> rows = new LinkedHashMap<>(Map.of("THICK", new ArrayList<>(), "NTC", new ArrayList<>()));
        Map<String, Set<String>> sources = new HashMap<>(Map.of("THICK", new TreeSet<>(), "NTC", new TreeSet<>()));
        Map<String, Integer> bad = new HashMap<>(Map.of("THICK", 0, "NTC", 0));
        for (HistoricalLoan h : loans) {
            EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
            String kind = in.bureau() != null ? "THICK" : "NTC";
            EngineResult r = UnderwritingEngine.underwrite(in, pol, modelFor(in));
            rows.get(kind).add(Map.of("features", Features.of(in), "bad", h.isOutcomeBad(), "championPd", r.pd()));
            sources.get(kind).add(h.getSource());
            if (h.isOutcomeBad()) bad.merge(kind, 1, Integer::sum);
        }
        List<ModelVersion> trained = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String kind : List.of("THICK", "NTC")) {
            List<Map<String, Object>> set = rows.get(kind);
            if (set.size() < MIN_ROWS || bad.get(kind) < MIN_BAD) {
                skipped.add(kind + ": " + set.size() + " loans with " + bad.get(kind) + " defaults (need " + MIN_ROWS + " and " + MIN_BAD + ")");
                continue;
            }
            trained.add(trainOne(kind, set, sources.get(kind), by));
        }
        if (trained.isEmpty())
            throw ApiException.unprocessable("NOT_ENOUGH_DATA", "Not enough past loans with outcomes to train: " + String.join("; ", skipped));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("candidates", trained);
        out.put("skipped", skipped);
        return out;
    }

    private ModelVersion trainOne(String kind, List<Map<String, Object>> rows, Set<String> sources, CurrentUser by) {
        List<String> feats = Features.ALL.stream().map(Features.Def::key)
                .filter(f -> "THICK".equals(kind) ? !"thinFile".equals(f) : !BUREAU_FEATURES.contains(f)).toList();
        Map<String, Object> out = trainer.train(Map.of("features", feats, "rows", rows, "l2", 1.0));
        String name = "THICK".equals(kind) ? NAME_THICK : NAME_NTC;
        PdModel current = champion("THICK".equals(kind)).orElse(null);

        ModelVersion v = new ModelVersion();
        v.setName(name);
        v.setKind(kind);
        v.setVersion("1." + (repo.countByName(name) + 1));
        v.setStatus("CANDIDATE");
        v.setIntercept(((Number) out.get("intercept")).doubleValue());
        v.setTermsJson(Json.write(out.get("terms")));
        Map<String, Object> metrics = new LinkedHashMap<>(out);
        metrics.remove("terms");
        metrics.remove("intercept");
        metrics.put("championName", current == null ? ("THICK".equals(kind) ? UnderwritingEngine.MODEL_THICK : UnderwritingEngine.MODEL_NTC) + " (built-in)" : current.label());
        v.setMetricsJson(Json.write(metrics));
        v.setDataSource(sources.size() > 1 ? "MIXED" : sources.isEmpty() ? "NONE" : sources.iterator().next());
        v.setRowsUsed(rows.size());
        v.setTrainedBy(by.username());
        repo.save(v);
        Object hold = metrics.get("holdout");
        audit.record(by, "model.trained", "MODEL", v.getName() + " " + v.getVersion(), null,
                rows.size() + " loans (" + v.getDataSource() + ") · hold-out " + (hold instanceof Map<?, ?> h ? "AUC " + h.get("auc") : "") +
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
            repo.findFirstByKindAndStatusOrderByIdDesc(v.getKind(), "CHAMPION").ifPresent(old -> { old.setStatus("RETIRED"); repo.save(old); });
            v.setStatus("CHAMPION");
            audit.record(by, "model.approved_champion", "MODEL", v.getName() + " " + v.getVersion(), null, note);
        } else {
            v.setStatus("REJECTED");
            audit.record(by, "model.rejected", "MODEL", v.getName() + " " + v.getVersion(), null, note);
        }
        repo.save(v);
        champions = null; // reload on next use
        return v;
    }

    /** Back to the built-in models (for example if a champion misbehaves in production). */
    @Transactional
    public void retireChampion(String note, CurrentUser by) {
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can retire the champion model");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        List<ModelVersion> cs = new ArrayList<>();
        for (String k : List.of("THICK", "NTC")) repo.findFirstByKindAndStatusOrderByIdDesc(k, "CHAMPION").ifPresent(cs::add);
        if (cs.isEmpty()) throw ApiException.conflict("NO_CHAMPION", "No trained model is in use");
        for (ModelVersion c : cs) {
            c.setStatus("RETIRED");
            c.setDecisionNote(note.trim());
            repo.save(c);
            audit.record(by, "model.champion_retired", "MODEL", c.getName() + " " + c.getVersion(), null, note.trim());
        }
        champions = null;
    }
}
