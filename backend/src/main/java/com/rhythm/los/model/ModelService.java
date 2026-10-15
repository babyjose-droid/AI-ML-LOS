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
    /** The separate fraud model: same governance (candidate, CRO approval, champion), its own features and labels. */
    public static final String NAME_FRAUD = "RHY-FRAUD-TRAINED";
    static final int MIN_FRAUD = 20;
    static final Set<Role> TRAINERS = EnumSet.of(Role.CREDIT_MANAGER, Role.CRO, Role.ADMIN);
    static final int MIN_ROWS = 200, MIN_BAD = 20;
    /** Bureau features have no meaning for a new-to-credit applicant, so the NTC model does not use them. */
    static final Set<String> BUREAU_FEATURES = Set.of("thinFile", "bureauScoreGap", "dpd12", "ever90", "enquiries6m", "historyYears");

    private final ModelVersionRepository repo;
    private final HistoricalLoanRepository book;
    private final PolicyService policy;
    private final ModelTrainer trainer;
    private final AuditService audit;
    /** Champion models per tenant. */
    private final Map<String, Map<String, Optional<PdModel>>> championsByTenant = new java.util.concurrent.ConcurrentHashMap<>();

    public ModelService(ModelVersionRepository repo, HistoricalLoanRepository book, PolicyService policy, ModelTrainer trainer, AuditService audit) {
        this.repo = repo;
        this.book = book;
        this.policy = policy;
        this.trainer = trainer;
        this.audit = audit;
    }

    /** The approved model for applicants with a bureau record (thick) or without one (new to credit); empty = built-in. */
    public Optional<PdModel> champion(boolean thick) {
        Map<String, Optional<PdModel>> c = championsByTenant.get(com.rhythm.los.tenant.TenantContext.code());
        if (c == null) {
            c = new HashMap<>();
            for (String k : List.of("THICK", "NTC", "FRAUD"))
                c.put(k, repo.findFirstByKindAndStatusOrderByIdDesc(k, "CHAMPION").map(ModelService::toModel));
            championsByTenant.put(com.rhythm.los.tenant.TenantContext.code(), c);
        }
        return c.get(thick ? "THICK" : "NTC");
    }

    /** The approved fraud model; empty = the built-in fraud score. */
    public Optional<PdModel> fraudChampion() {
        champion(true);
        return championsByTenant.get(com.rhythm.los.tenant.TenantContext.code()).getOrDefault("FRAUD", Optional.empty());
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
        for (Map<String, Object> t : terms) t.put("label", "FRAUD".equals(v.getKind()) ? com.rhythm.los.decision.engine.FraudFeatures.label((String) t.get("feature"))
                : Features.def((String) t.get("feature")).label());
        m.put("terms", terms);
        m.put("metrics", Json.read(v.getMetricsJson(), Map.class));
        return m;
    }

    /**
     * Builds the training sets from the loan book, one for applicants with a bureau record and one without, and asks the
     * trainer for a candidate of each. A set too small to train on is skipped and reported.
     */
    @Transactional
    public Map<String, Object> train(CurrentUser by) { return train(by, false); }

    /** onlyOwn: train on the lender's imported loans only (no synthetic or demo data). */
    @Transactional
    public Map<String, Object> train(CurrentUser by, boolean onlyOwn) {
        if (!TRAINERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Only credit managers and the CRO can train models");
        List<HistoricalLoan> loans = book.findAllByOrderByIdAsc().stream().filter(h -> !onlyOwn || "IMPORT".equals(h.getSource())).toList();
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

    private com.rhythm.los.audit.AuditRepository auditRepo;
    private com.rhythm.los.decision.DecisionRecordRepository decisionsForFraud;

    @org.springframework.beans.factory.annotation.Autowired
    void setFraudSources(com.rhythm.los.audit.AuditRepository a, @org.springframework.context.annotation.Lazy com.rhythm.los.decision.DecisionRecordRepository d) {
        this.auditRepo = a;
        this.decisionsForFraud = d;
    }

    /**
     * Synthetic loans have no fraud outcome; for the demo one is simulated from the built-in fraud score (deterministic per
     * loan). Imported loans carry the lender's own fraud flag.
     */
    static boolean fraudLabel(HistoricalLoan h, EngineInput in) {
        if (!"SYNTHETIC".equals(h.getSource())) return h.isOutcomeFraud();
        double u = (Math.abs(h.getRef().hashCode() * 2654435761L) % 100000) / 100000.0;
        return u < com.rhythm.los.decision.engine.FraudFeatures.builtInProbability(in);
    }

    /**
     * Trains the fraud model: past loans with a fraud outcome, plus every application a fraud analyst confirmed (fraud)
     * or cleared (not fraud) here, so the model learns from the lender's own reviews.
     */
    @Transactional
    public Map<String, Object> trainFraud(CurrentUser by, boolean onlyOwn) {
        if (!TRAINERS.contains(by.role())) throw ApiException.forbidden("NOT_ALLOWED", "Only credit managers and the CRO can train models");
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> sources = new TreeSet<>();
        int frauds = 0, reviewed = 0;
        for (HistoricalLoan h : book.findAllByOrderByIdAsc()) {
            if (onlyOwn && !"IMPORT".equals(h.getSource())) continue;
            EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
            boolean f = fraudLabel(h, in);
            rows.add(Map.of("features", com.rhythm.los.decision.engine.FraudFeatures.of(in), "bad", f,
                    "championPd", com.rhythm.los.decision.engine.FraudFeatures.builtInProbability(in)));
            sources.add(h.getSource());
            if (f) frauds++;
        }
        if (auditRepo != null && decisionsForFraud != null) {
            Map<Long, Boolean> label = new HashMap<>();
            for (var e : auditRepo.findByActionIn(List.of("fraud.confirmed", "fraud.cleared")))
                if (e.getApplicationId() != null) label.put(e.getApplicationId(), "fraud.confirmed".equals(e.getAction()));
            for (var x : label.entrySet()) {
                var d = decisionsForFraud.findFirstByApplicationIdOrderByIdDesc(x.getKey()).orElse(null);
                if (d == null || d.getInputJson() == null) continue;
                EngineInput in = Json.read(d.getInputJson(), EngineInput.class);
                rows.add(Map.of("features", com.rhythm.los.decision.engine.FraudFeatures.of(in), "bad", x.getValue(),
                        "championPd", com.rhythm.los.decision.engine.FraudFeatures.builtInProbability(in)));
                sources.add("REVIEWED");
                reviewed++;
                if (x.getValue()) frauds++;
            }
        }
        if (rows.size() < MIN_ROWS || frauds < MIN_FRAUD)
            throw ApiException.unprocessable("NOT_ENOUGH_DATA", "Not enough cases to train a fraud model: " + rows.size() + " loans and reviews with " + frauds
                    + " confirmed frauds (need " + MIN_ROWS + " and " + MIN_FRAUD + ")");
        Map<String, Object> out = trainer.train(Map.of("features", com.rhythm.los.decision.engine.FraudFeatures.ALL, "rows", rows, "l2", 1.0));
        PdModel current = fraudChampion().orElse(null);
        ModelVersion v = new ModelVersion();
        v.setName(NAME_FRAUD);
        v.setKind("FRAUD");
        v.setVersion("1." + (repo.countByName(NAME_FRAUD) + 1));
        v.setStatus("CANDIDATE");
        v.setIntercept(((Number) out.get("intercept")).doubleValue());
        v.setTermsJson(Json.write(out.get("terms")));
        Map<String, Object> metrics = new LinkedHashMap<>(out);
        metrics.remove("terms");
        metrics.remove("intercept");
        metrics.put("championName", current == null ? "Built-in fraud score" : current.label());
        metrics.put("frauds", frauds);
        metrics.put("analystReviews", reviewed);
        v.setMetricsJson(Json.write(metrics));
        v.setDataSource(sources.size() > 1 ? "MIXED" : sources.isEmpty() ? "NONE" : sources.iterator().next());
        v.setRowsUsed(rows.size());
        v.setTrainedBy(by.username());
        v.setBaselineJson(Json.write(baselineOf(rows, com.rhythm.los.decision.engine.FraudFeatures.ALL, toModelFrom(NAME_FRAUD, v.getVersion(), v.getIntercept(), v.getTermsJson()))));
        repo.save(v);
        Object hold = metrics.get("holdout");
        audit.record(by, "model.trained", "MODEL", v.getName() + " " + v.getVersion(), null,
                rows.size() + " cases, " + frauds + " frauds (" + v.getDataSource() + ") · hold-out " + (hold instanceof Map<?, ?> hm ? "AUC " + hm.get("auc") : ""));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("candidate", v);
        res.put("frauds", frauds);
        res.put("analystReviews", reviewed);
        return res;
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
        v.setBaselineJson(Json.write(baselineOf(rows, feats, toModelFrom(name, v.getVersion(), v.getIntercept(), v.getTermsJson()))));
        repo.save(v);
        Object hold = metrics.get("holdout");
        audit.record(by, "model.trained", "MODEL", v.getName() + " " + v.getVersion(), null,
                rows.size() + " loans (" + v.getDataSource() + ") · hold-out " + (hold instanceof Map<?, ?> h ? "AUC " + h.get("auc") : "") +
                        (metrics.get("aucGain") != null ? " · gain over champion " + metrics.get("aucGain") : ""));
        return v;
    }

    static PdModel toModelFrom(String name, String version, double intercept, String termsJson) {
        ModelVersion t = new ModelVersion();
        t.setName(name);
        t.setVersion(version);
        t.setIntercept(intercept);
        t.setTermsJson(termsJson);
        return toModel(t);
    }

    static double pdOf(PdModel m, Map<String, Double> fx) {
        double z = m.intercept();
        for (PdModel.Term t : m.terms()) z += t.weight() * (fx.getOrDefault(t.feature(), 0.0) - t.mean());
        return 1 / (1 + Math.exp(-z));
    }

    /** Training distribution of every feature (and of the model's PD) for drift monitoring. */
    @SuppressWarnings("unchecked")
    static Map<String, Drift.Baseline> baselineOf(List<Map<String, Object>> rows, List<String> feats, PdModel m) {
        Map<String, Drift.Baseline> out = new LinkedHashMap<>();
        for (String f : feats) {
            List<Double> vals = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object x = ((Map<String, Double>) r.get("features")).get(f);
                if (x != null) vals.add(((Number) x).doubleValue());
            }
            out.put(f, Drift.baseline(vals));
        }
        if (m != null) out.put("pd", Drift.baseline(rows.stream().map(r -> pdOf(m, (Map<String, Double>) r.get("features"))).toList()));
        return out;
    }

    // ---------------------------------------------------------------- shadow and monitoring

    private ShadowScoreRepository shadows;
    private com.rhythm.los.decision.DecisionRecordRepository decisionRecords;

    @org.springframework.beans.factory.annotation.Autowired
    void setMonitoring(ShadowScoreRepository shadows, @org.springframework.context.annotation.Lazy com.rhythm.los.decision.DecisionRecordRepository decisionRecords) {
        this.shadows = shadows;
        this.decisionRecords = decisionRecords;
    }

    /**
     * Shadow mode: every candidate model of the applicant's kind scores the same input as the champion, without acting.
     * The CRO sees how often it agrees before approving it.
     */
    public void shadow(Long decisionId, Long appId, EngineInput in, PolicyParams pol, EngineResult champion) {
        if (shadows == null) return;
        String kind = in.bureau() != null ? "THICK" : "NTC";
        for (ModelVersion c : repo.findByKindAndStatus(kind, "CANDIDATE")) {
            try {
                EngineResult r = UnderwritingEngine.underwrite(in, pol, toModel(c));
                shadows.save(new ShadowScore(decisionId, appId, c.getId(), r.pd(), r.band(), r.decision(), champion.pd(), champion.band(), champion.decision()));
            } catch (RuntimeException e) {
                // a broken candidate must never stop a decision
            }
        }
    }

    private final Map<String, Map<String, Map<String, Drift.Baseline>>> baselinesByTenant = new java.util.concurrent.ConcurrentHashMap<>();

    /** Baselines from the loan book for the built-in models (they were not trained here). */
    Map<String, Drift.Baseline> bookBaseline(String kind) {
        Map<String, Map<String, Drift.Baseline>> b = baselinesByTenant.get(com.rhythm.los.tenant.TenantContext.code());
        if (b == null) {
            b = new HashMap<>();
            Map<String, List<Map<String, Object>>> rows = new HashMap<>(Map.of("THICK", new ArrayList<>(), "NTC", new ArrayList<>()));
            for (HistoricalLoan h : book.findAllByOrderByIdAsc()) {
                EngineInput in = Json.read(h.getInputJson(), EngineInput.class);
                rows.get(in.bureau() != null ? "THICK" : "NTC").add(Map.of("features", Features.of(in)));
            }
            for (String k : List.of("THICK", "NTC")) {
                List<String> feats = Features.ALL.stream().map(Features.Def::key)
                        .filter(f -> "THICK".equals(k) ? !"thinFile".equals(f) : !BUREAU_FEATURES.contains(f)).toList();
                b.put(k, baselineOf(rows.get(k), feats, null));
            }
            baselinesByTenant.put(com.rhythm.los.tenant.TenantContext.code(), b);
        }
        return b.get(kind);
    }

    /** Drift (PSI per feature and of the PD) on live decisions, and shadow agreement for a candidate. */
    public Map<String, Object> monitoring(Long id) {
        ModelVersion v = get(id);
        Map<String, Drift.Baseline> base = v.getBaselineJson() == null ? bookBaseline(v.getKind())
                : Json.read(v.getBaselineJson(), new TypeReference<LinkedHashMap<String, Drift.Baseline>>() {});
        Map<String, Object> out = monitor(v.getKind(), base, v.getBaselineJson() == null ? "loan book" : "training data");
        if (shadows != null) out.put("shadow", shadowStats(v.getId()));
        return out;
    }

    public Map<String, Object> monitoringBuiltIn(String kind) {
        if (!List.of("THICK", "NTC").contains(kind)) throw ApiException.unprocessable("BAD_KIND", "Kind is THICK or NTC");
        return monitor(kind, bookBaseline(kind), "loan book");
    }

    private Map<String, Object> monitor(String kind, Map<String, Drift.Baseline> base, String reference) {
        Map<Long, com.rhythm.los.decision.DecisionRecord> latest = new HashMap<>();
        if (decisionRecords != null)
            for (var d : decisionRecords.findAll()) latest.merge(d.getApplicationId(), d, (x, y) -> x.getId() > y.getId() ? x : y);
        Map<String, List<Double>> live = new HashMap<>();
        for (var d : latest.values()) {
            if (d.getInputJson() == null || "INCOMPLETE".equals(d.getDecision())) continue;
            EngineInput in;
            try { in = Json.read(d.getInputJson(), EngineInput.class); } catch (Exception e) { continue; }
            if ("FRAUD".equals(kind)) {
                com.rhythm.los.decision.engine.FraudFeatures.of(in).forEach((k, x) -> live.computeIfAbsent(k, z -> new ArrayList<>()).add(x));
                live.computeIfAbsent("pd", z -> new ArrayList<>()).add(d.getFraudScore() / 100.0);
                continue;
            }
            if ((in.bureau() != null) != "THICK".equals(kind)) continue;
            Features.of(in).forEach((k, x) -> live.computeIfAbsent(k, z -> new ArrayList<>()).add(x));
            live.computeIfAbsent("pd", z -> new ArrayList<>()).add(d.getPd().doubleValue());
        }
        List<Drift.Psi> psi = new ArrayList<>();
        base.forEach((f, b) -> psi.add(Drift.psi(f, "pd".equals(f) ? ("FRAUD".equals(kind) ? "Fraud probability" : "Predicted PD")
                : "FRAUD".equals(kind) ? com.rhythm.los.decision.engine.FraudFeatures.label(f) : Features.def(f).label(), b, live.getOrDefault(f, List.of()))));
        psi.sort(Comparator.comparingDouble(Drift.Psi::psi).reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("reference", reference);
        out.put("liveDecisions", live.getOrDefault("pd", List.of()).size());
        out.put("drifting", psi.stream().filter(x -> "DRIFT".equals(x.status())).count());
        out.put("psi", psi);
        return out;
    }

    Map<String, Object> shadowStats(Long modelId) {
        List<ShadowScore> s = shadows.findByModelVersionIdOrderByIdDesc(modelId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scored", s.size());
        if (s.isEmpty()) return m;
        long bandSame = s.stream().filter(x -> x.getBand().equals(x.getChampionBand())).count();
        long decSame = s.stream().filter(x -> x.getDecision().equals(x.getChampionDecision())).count();
        double diff = s.stream().mapToDouble(x -> x.getPd().doubleValue() - x.getChampionPd().doubleValue()).average().orElse(0);
        m.put("bandAgreement", Math.round(1000.0 * bandSame / s.size()) / 1000.0);
        m.put("decisionAgreement", Math.round(1000.0 * decSame / s.size()) / 1000.0);
        m.put("avgPdDifference", Math.round(diff * 10000) / 10000.0);
        m.put("disagreements", s.stream().filter(x -> !x.getDecision().equals(x.getChampionDecision())).limit(20).map(x -> Map.of(
                "applicationId", x.getApplicationId(), "champion", x.getChampionDecision() + " · " + x.getChampionBand(),
                "candidate", x.getDecision() + " · " + x.getBand(), "championPd", x.getChampionPd(), "candidatePd", x.getPd())).toList());
        return m;
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
        championsByTenant.remove(com.rhythm.los.tenant.TenantContext.code()); // reload on next use
        return v;
    }

    /** Back to the built-in models (for example if a champion misbehaves in production). */
    @Transactional
    public void retireChampion(String note, CurrentUser by) {
        if (by.role() != Role.CRO) throw ApiException.forbidden("NOT_CHECKER", "Only the CRO can retire the champion model");
        if (note == null || note.trim().length() < 10) throw ApiException.unprocessable("REASON_REQUIRED", "Give a reason of at least 10 characters");
        List<ModelVersion> cs = new ArrayList<>();
        for (String k : List.of("THICK", "NTC", "FRAUD")) repo.findFirstByKindAndStatusOrderByIdDesc(k, "CHAMPION").ifPresent(cs::add);
        if (cs.isEmpty()) throw ApiException.conflict("NO_CHAMPION", "No trained model is in use");
        for (ModelVersion c : cs) {
            c.setStatus("RETIRED");
            c.setDecisionNote(note.trim());
            repo.save(c);
            audit.record(by, "model.champion_retired", "MODEL", c.getName() + " " + c.getVersion(), null, note.trim());
        }
        championsByTenant.remove(com.rhythm.los.tenant.TenantContext.code());
    }
}
