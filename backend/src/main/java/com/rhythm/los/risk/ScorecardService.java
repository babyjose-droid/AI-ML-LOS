package com.rhythm.los.risk;

import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.ApiException;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.security.CurrentUser;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/** Picks the industry scorecard for an application and scores it; seeds the built-in scorecards on first start. */
@Service
public class ScorecardService {
    static final Map<String, String> INDUSTRY_CARD = Map.of("KIRANA", "KIRANA", "TRADING", "KIRANA", "DAIRY", "DAIRY", "AGRI", "DAIRY",
            "TRANSPORT", "TRANSPORT", "SALARIED", "SALARIED");

    private final ScorecardRepository repo;
    private final AuditService audit;

    public ScorecardService(ScorecardRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        for (Scorecard.Definition d : Scorecard.builtIns()) {
            if (repo.existsById(d.code())) continue;
            ScorecardEntity e = new ScorecardEntity();
            e.setCode(d.code());
            e.setName(d.name());
            e.setIndustry(d.industry());
            e.setDefinitionJson(Json.write(d));
            e.setUpdatedBy("system");
            repo.save(e);
        }
    }

    public List<Scorecard.Definition> all() {
        return repo.findAll().stream().filter(ScorecardEntity::isActive).map(e -> Json.read(e.getDefinitionJson(), Scorecard.Definition.class))
                .sorted(Comparator.comparing(Scorecard.Definition::code)).toList();
    }

    public Optional<Scorecard.Definition> forApplication(LoanApplication a) {
        String ind = a.getIndustry() == null ? ("SALARIED".equals(a.getSegment()) ? "SALARIED" : "GENERAL") : a.getIndustry();
        String card = INDUSTRY_CARD.getOrDefault(ind, "GENERAL");
        List<Scorecard.Definition> all = all();
        return all.stream().filter(d -> d.industry().equals(card)).findFirst()
                .or(() -> all.stream().filter(d -> d.industry().equals("GENERAL")).findFirst());
    }

    public Optional<Scorecard.Result> score(LoanApplication a, EngineInput in, EngineResult r, Map<String, Double> field) {
        return forApplication(a).map(d -> Scorecard.score(d, Scorecard.values(in, r, field)));
    }

    /** Admin edits a scorecard's points; every band must be in order and points within ±200. */
    @Transactional
    public Scorecard.Definition update(String code, Scorecard.Definition d, CurrentUser by) {
        ScorecardEntity e = repo.findById(code).orElseThrow(() -> ApiException.notFound("Scorecard " + code));
        if (d == null || d.characteristics() == null || d.characteristics().isEmpty()) throw ApiException.unprocessable("EMPTY", "A scorecard needs characteristics");
        if (d.pdo() <= 0 || d.baseOdds() <= 0) throw ApiException.unprocessable("BAD_SCALING", "Odds and points-to-double-odds must be positive");
        for (Scorecard.Characteristic c : d.characteristics()) {
            if (c.bins() == null || c.bins().isEmpty() || c.bins().get(c.bins().size() - 1).max() != null)
                throw ApiException.unprocessable("BAD_BINS", c.label() + ": the last band must be open-ended");
            Double prev = null;
            for (Scorecard.Bin b : c.bins()) {
                if (Math.abs(b.points()) > 200) throw ApiException.unprocessable("BAD_POINTS", c.label() + ": points must be within ±200");
                if (b.max() != null && prev != null && b.max() <= prev) throw ApiException.unprocessable("BAD_BINS", c.label() + ": band limits must increase");
                if (b.max() != null) prev = b.max();
            }
        }
        Scorecard.Definition clean = new Scorecard.Definition(code, d.name() == null ? e.getName() : d.name(), e.getIndustry(), d.basePoints(), d.baseOdds(), d.pdo(), d.characteristics());
        e.setDefinitionJson(Json.write(clean));
        e.setName(clean.name());
        e.setUpdatedBy(by.username());
        e.setUpdatedAt(Instant.now());
        repo.save(e);
        audit.record(by, "scorecard.updated", "SCORECARD", code, null, clean.characteristics().size() + " characteristics");
        return clean;
    }
}
