package com.rhythm.los.decision;

import com.rhythm.los.decision.engine.EngineInput;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Stateless Decision API (FSD 15, FR-UW-07): the same engine, policy and champion model as the application flow, on an
 * input the caller supplies. Nothing is stored. The response has the same fields as an application's decision record.
 * Partner API keys come with the integrations phase; until then staff tokens with credit rights can call it.
 */
@RestController
@RequestMapping("/api/v1/credit")
public class DecisionApiController {
    public record DecisionApiRequest(String productCode, EngineInput input) {}

    private final DecisionService decisions;

    public DecisionApiController(DecisionService decisions) { this.decisions = decisions; }

    @PostMapping("/decision")
    @PreAuthorize("hasAnyRole('CREDIT_OFFICER','CREDIT_MANAGER','CRO','ADMIN')")
    public DecisionRecord decide(@RequestBody DecisionApiRequest req) {
        return decisions.evaluate(req.input(), req.productCode());
    }
}
