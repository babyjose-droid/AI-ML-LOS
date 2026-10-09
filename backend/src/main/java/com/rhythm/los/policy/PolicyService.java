package com.rhythm.los.policy;

import com.rhythm.los.common.Json;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {
    private final PolicyRepository repo;

    public PolicyService(PolicyRepository repo) { this.repo = repo; }

    /** The live credit policy. Falls back to defaults only if the table is empty (never in a seeded database). */
    public PolicyParams live() {
        return repo.findFirstByStatusOrderByIdDesc("LIVE")
                .map(p -> Json.read(p.getParamsJson(), PolicyParams.class))
                .orElse(PolicyParams.defaults());
    }
}
