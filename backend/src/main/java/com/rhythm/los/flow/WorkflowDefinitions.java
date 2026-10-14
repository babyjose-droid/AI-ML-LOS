package com.rhythm.los.flow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.rhythm.los.application.LoanApplication;
import com.rhythm.los.common.Json;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reads workflow definitions. Has no dependency on application services, so they can use it freely. */
@Component
public class WorkflowDefinitions {
    static final List<String> DEFAULT = List.of("lead", "kyc", "docs", "field", "uw", "cam", "sanction", "disb");
    private final WorkflowVersionRepository versions;
    private final Map<String, List<Stage>> cache = new ConcurrentHashMap<>();

    public WorkflowDefinitions(WorkflowVersionRepository versions) { this.versions = versions; }

    public Optional<WorkflowVersion> published(String productCode) {
        return versions.findFirstByProductCodeAndStatusOrderByVersionDesc(productCode, "PUBLISHED");
    }

    /**
     * The stages an application follows: always the version pinned when it was created, so publishing a new version
     * never changes applications already in flight. An application created before its product had any published
     * workflow follows the built-in default sequence (it is never moved onto a later version).
     */
    public List<Stage> stages(LoanApplication a) {
        Integer v = a.getWorkflowVersion();
        if (v == null) return DEFAULT.stream().map(StageLibrary::defaults).toList();
        String key = com.rhythm.los.tenant.TenantContext.code() + ":" + a.getProductCode() + "#" + v;
        List<Stage> hit = cache.get(key);
        if (hit != null) return hit;
        List<Stage> s = versions.findByProductCodeAndVersion(a.getProductCode(), v).map(x -> parse(x.getStagesJson()))
                .orElseGet(() -> DEFAULT.stream().map(StageLibrary::defaults).toList());
        cache.put(key, s);
        return s;
    }

    public static List<Stage> parse(String json) {
        return Json.read(json, new TypeReference<List<Stage>>() {});
    }

    /** Facts known from the application alone (no decision yet). */
    public static Map<String, Object> basicFacts(LoanApplication a, boolean existingCustomer) {
        Map<String, Object> f = new HashMap<>();
        f.put("amount", a.getLoanAmount() == null ? null : a.getLoanAmount().doubleValue());
        f.put("tenure", (double) a.getTenureMonths());
        f.put("segment", a.getSegment());
        f.put("product", a.getProductCode());
        f.put("channel", a.getChannel());
        f.put("age", a.getDob() == null ? null : (double) Period.between(a.getDob(), LocalDate.now()).getYears());
        f.put("income", a.getDeclaredMonthlyIncome() == null ? null : a.getDeclaredMonthlyIncome().doubleValue());
        f.put("existingCustomer", existingCustomer);
        return f;
    }

    /** True when the field visit stage is in the workflow and its skip rule does not apply. */
    public boolean fieldStageApplies(LoanApplication a, boolean existingCustomer) {
        for (Stage s : stages(a)) {
            if ("field".equals(s.key())) return !Conditions.eval(s.skip(), basicFacts(a, existingCustomer));
        }
        return false;
    }
}
