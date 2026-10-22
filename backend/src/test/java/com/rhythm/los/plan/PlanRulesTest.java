package com.rhythm.los.plan;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PlanRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    static final List<PlanRules.Tranche> STAGES = List.of(
            new PlanRules.Tranche(1, "Plinth", 300_000, 0d, "BORROWER", null), new PlanRules.Tranche(2, "Roof slab", 450_000, 50d, "BORROWER", null),
            new PlanRules.Tranche(3, "Brickwork and plaster", 450_000, 80d, "BORROWER", null), new PlanRules.Tranche(4, "Finishing", 300_000, 100d, "BORROWER", null));

    static PlanRules.Check check(PlanRules.Result r, String id) { return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void housingConstructionPlanPasses() {
        Map<String, Object> d = Map.of("purpose", "CONSTRUCTION", "estimate", 1_800_000, "ownContribution", 300_000, "carpetAreaSqft", 860, "planNo", "NMC/BP/2026/1187");
        var r = PlanRules.evaluate("HOUSING", d, Map.of("CONSTRUCTION_ESTIMATE", Map.of("total", 1_800_000, "carpetAreaSqft", 860, "planNo", "NMC/BP/2026/1187", "engineer", "Er. S Wagh")),
                STAGES, null, "Sachin More", 1_500_000, 180, TODAY, null);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(check(r, "HS-03").reason()).contains("83.3%");
    }

    @Test
    void housingStagesMustAddUpAndAboveLtvRefers() {
        Map<String, Object> d = Map.of("purpose", "CONSTRUCTION", "estimate", 1_500_000, "ownContribution", 0, "carpetAreaSqft", 1100, "planNo", "X");
        var r = PlanRules.evaluate("HOUSING", d, Map.of(), STAGES.subList(0, 3), null, "Sachin More", 1_500_000, 180, TODAY, null);
        assertThat(check(r, "HS-05").result()).isEqualTo("FAIL");
        assertThat(check(r, "HS-03").result()).isEqualTo("REFER");
        assertThat(check(r, "HS-02").result()).isEqualTo("REFER");
        assertThat(check(r, "HS-07").result()).isEqualTo("PENDING");
    }

    @Test
    void educationFeesToApprovedInstitution() {
        var inst = new PlanRules.Party(1L, "Kalaram Institute of Nursing Sciences", "INSTITUTION", "ACTIVE", true, true);
        Map<String, Object> d = Map.of("studentName", "Aditi Patwardhan", "relationship", "CHILD", "courseStart", "2026-08-01", "durationMonths", 48);
        Map<String, Map<String, Object>> docs = Map.of(
                "ADMISSION_LETTER", Map.of("student", "ADITI PATWARDHAN", "institution", "Kalaram Institute of Nursing Sciences", "course", "B.Sc Nursing"),
                "FEE_LETTER", Map.of("total", 320_000, "terms", List.of(Map.of("term", 1, "amount", 160_000), Map.of("term", 2, "amount", 160_000))));
        List<PlanRules.Tranche> tr = List.of(new PlanRules.Tranche(1, "Term 1", 160_000, null, "INSTITUTION", 1L), new PlanRules.Tranche(2, "Term 2", 160_000, null, "INSTITUTION", 1L));
        var r = PlanRules.evaluate("EDUCATION", d, docs, tr, inst, "Meena Patwardhan", 320_000, 24, TODAY, null);
        assertThat(r.status()).isEqualTo("PASS");
        var suspended = new PlanRules.Party(4L, "Rapid Career Academy", "INSTITUTION", "SUSPENDED", false, true);
        assertThat(check(PlanRules.evaluate("EDUCATION", d, docs, tr, suspended, "Meena Patwardhan", 320_000, 24, TODAY, null), "ED-01").result()).isEqualTo("FAIL");
    }

    @Test
    void farmScaleOfFinanceAndLandRecord() {
        Map<String, Object> d = new HashMap<>(Map.of("activity", "CROP", "landAreaAcres", 4, "scalePerUnit", 65_000, "ownContribution", 30_000, "harvestMonths", "NOV, MAY", "insuranceRef", "PMFBY/26/KH/77812"));
        Map<String, Map<String, Object>> docs = Map.of("LAND_RECORD_712", Map.of("owner", "Vitthal Shelke", "surveyNo", "418/2", "areaAcres", 4.0, "village", "Pimpalgaon Baswant"));
        var r = PlanRules.evaluate("AGRI", d, docs, List.of(), null, "Vitthal Shelke", 240_000, 24, TODAY, null);
        assertThat(r.status()).isEqualTo("PASS");
        var other = PlanRules.evaluate("AGRI", d, Map.of("LAND_RECORD_712", Map.of("owner", "Ramdas Shelke", "areaAcres", 4.0)), List.of(), null, "Vitthal Shelke", 300_000, 24, TODAY, null);
        assertThat(check(other, "AG-01").result()).isEqualTo("REFER");
        assertThat(check(other, "AG-02").result()).isEqualTo("REFER");
    }

    @Test
    void trancheReleaseNeedsCertifiedProgressOrFeeDemand() {
        var t2 = STAGES.get(1);
        assertThat(PlanRules.release("HOUSING", t2, true, null, null, 300_000, 1_500_000, null).status()).isEqualTo("PENDING");
        assertThat(PlanRules.release("HOUSING", t2, true, 40d, null, 300_000, 1_500_000, null).status()).isEqualTo("FAIL");
        assertThat(PlanRules.release("HOUSING", t2, true, 55d, null, 300_000, 1_500_000, null).status()).isEqualTo("PASS");
        assertThat(PlanRules.release("HOUSING", t2, false, 55d, null, 300_000, 1_500_000, null).status()).isEqualTo("PENDING");
        var inst = new PlanRules.Party(1L, "Kalaram Institute of Nursing Sciences", "INSTITUTION", "ACTIVE", true, true);
        var term2 = new PlanRules.Tranche(2, "Term 2", 160_000, null, "INSTITUTION", 1L);
        assertThat(PlanRules.release("EDUCATION", term2, true, null, null, 160_000, 320_000, inst).status()).isEqualTo("PENDING");
        assertThat(PlanRules.release("EDUCATION", term2, true, null, 160_000d, 160_000, 320_000, inst).status()).isEqualTo("PASS");
    }

    @Test
    void namesMatchIgnoringCaseAndTitles() {
        assertThat(PlanRules.sameName("Shri. VITTHAL SHELKE", "Vitthal Shelke")).isTrue();
        assertThat(PlanRules.sameName("Ramdas Shelke", "Vitthal Shelke")).isFalse();
    }
}
