package com.rhythm.los.policy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.audit.AuditService;
import com.rhythm.los.common.Json;
import com.rhythm.los.security.CurrentUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The credit policy in force. A version approved with a future effective date waits as APPROVED and takes over from
 * the live version when its date arrives. Per-segment and per-product overrides (FR-POL-02) change single parameters
 * for that segment or product: base policy, then the segment's overrides, then the product's.
 */
@Service
public class PolicyService {
    private final PolicyRepository repo;
    private final AuditService audit;

    public PolicyService(PolicyRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** The version in force now: a scheduled version whose date has arrived wins even before the job flips its status. */
    public Optional<PolicyVersion> current() {
        Instant now = Instant.now();
        Optional<PolicyVersion> due = repo.findAllByOrderByIdDesc().stream()
                .filter(v -> "APPROVED".equals(v.getStatus()) && v.getEffectiveFrom() != null && !v.getEffectiveFrom().isAfter(now))
                .max(Comparator.comparing(PolicyVersion::getEffectiveFrom));
        return due.isPresent() ? due : repo.findFirstByStatusOrderByIdDesc("LIVE");
    }

    /** The live credit policy. Falls back to defaults only if the table is empty (never in a seeded database). */
    public PolicyParams live() {
        return current().map(p -> Json.read(p.getParamsJson(), PolicyParams.class)).orElse(PolicyParams.defaults());
    }

    /** The live policy with the overrides for this product and segment applied. */
    public PolicyParams liveFor(String productCode, String segment) {
        return current().map(p -> apply(Json.read(p.getParamsJson(), PolicyParams.class), p.getOverridesJson(), productCode, segment))
                .orElse(PolicyParams.defaults());
    }

    public static Map<String, Map<String, Number>> overrides(String json) {
        if (json == null || json.isBlank()) return new TreeMap<>();
        return Json.read(json, new TypeReference<TreeMap<String, Map<String, Number>>>() {});
    }

    /** Base parameters, then "segment:X" overrides, then "product:Y" overrides. The version name says an override applied. */
    public static PolicyParams apply(PolicyParams base, String overridesJson, String productCode, String segment) {
        Map<String, Map<String, Number>> o = overrides(overridesJson);
        Map<String, Number> seg = segment == null ? null : o.get("segment:" + segment);
        Map<String, Number> prod = productCode == null ? null : o.get("product:" + productCode);
        if ((seg == null || seg.isEmpty()) && (prod == null || prod.isEmpty())) return base;
        Map<String, Object> m = PolicyFields.asMap(base);
        List<String> tags = new ArrayList<>();
        if (seg != null && !seg.isEmpty()) { m.putAll(seg); tags.add(segment); }
        if (prod != null && !prod.isEmpty()) { m.putAll(prod); tags.add(productCode); }
        m.put("version", base.version() + "+" + String.join("+", tags));
        return Json.MAPPER.convertValue(m, PolicyParams.class);
    }

    /** Puts a scheduled version live when its effective date arrives; the previous live version is retired. */
    @Scheduled(fixedDelayString = "${rhythm.policy.activation-check-ms:60000}", initialDelayString = "${rhythm.policy.activation-initial-delay-ms:5000}")
    @Transactional
    public void activateDue() {
        Instant now = Instant.now();
        for (PolicyVersion v : repo.findAllByOrderByIdDesc()) {
            if (!"APPROVED".equals(v.getStatus()) || v.getEffectiveFrom() == null || v.getEffectiveFrom().isAfter(now)) continue;
            repo.findFirstByStatusOrderByIdDesc("LIVE").ifPresent(old -> {
                old.setStatus("RETIRED");
                old.setRetiredAt(v.getEffectiveFrom());
                repo.save(old);
            });
            v.setStatus("LIVE");
            v.setLiveFrom(v.getEffectiveFrom());
            repo.save(v);
            audit.record(CurrentUser.SYSTEM, "policy.effective", "POLICY", v.getVersion(), null, "Became live on its effective date");
        }
    }
}
