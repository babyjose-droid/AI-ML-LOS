package com.rhythm.los.scale;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScaleRulesTest {
    static final CoLendingRules.Partner MSME = new CoLendingRules.Partner("PB", List.of("BL-UNS", "LAP"), 2_500_000, List.of("A", "B", "C"), false, 3, true, 0.8, 9.5);

    @Test
    void coLendingEligibilityReasons() {
        assertThat(CoLendingRules.reasons(MSME, new CoLendingRules.Loan("BL-UNS", 800_000, 18, "B", false, 5, true))).isEmpty();
        List<String> r = CoLendingRules.reasons(MSME, new CoLendingRules.Loan("PL", 3_000_000, 9, "D", true, 1, false));
        assertThat(r).hasSize(7);
        assertThat(r).anyMatch(x -> x.contains("Product PL")).anyMatch(x -> x.contains("maximum ticket")).anyMatch(x -> x.contains("band D"))
                .anyMatch(x -> x.contains("New-to-credit")).anyMatch(x -> x.contains("vintage")).anyMatch(x -> x.contains("PSL")).anyMatch(x -> x.contains("below the partner"));
        // blended: borrower pays 18%; the partner earns 9.5% on 80%, so the originator earns 52% on its 20%
        assertThat(CoLendingRules.nbfcYield(18, 0.8, 9.5)).isEqualTo(52.0);
        assertThat(CoLendingRules.nbfcYield(14, 0.8, 12)).isEqualTo(22.0);
    }

    @Test
    void capitalPlanFlagsCashShortfallAndKeepsCrar() {
        Map<String, Object> fast = CapitalPlanner.project(new CapitalPlanner.Inputs(25e6, 80e6, 5e6, 100e6, 8e6, 3, 0.97, 20, 11, 2, 4, 3e6, 2e6, 24, 0.15, 1.0, 12));
        assertThat(((Number) fast.get("minCash")).doubleValue()).isNegative();
        assertThat((int) fast.get("minCashMonth")).isEqualTo(12);
        assertThat((List<?>) fast.get("alerts")).isNotEmpty();
        assertThat(fast.get("alerts").toString()).contains("Month 3: Cash negative");
        assertThat(((Number) fast.get("minCrar")).doubleValue()).isGreaterThan(0.15);
        assertThat((List<?>) fast.get("months")).hasSize(12);
        Map<String, Object> steady = CapitalPlanner.project(new CapitalPlanner.Inputs(25e6, 80e6, 20e6, 100e6, 3e6, 0, 0.98, 20, 11, 2, 4, 2e6, 2e6, 24, 0.15, 1.0, 12));
        assertThat((List<?>) steady.get("alerts")).isEmpty();
        // thin capital: CRAR below 15%
        Map<String, Object> thin = CapitalPlanner.project(new CapitalPlanner.Inputs(10e6, 90e6, 20e6, 100e6, 3e6, 0, 0.98, 20, 11, 2, 4, 2e6, 2e6, 24, 0.15, 1.0, 6));
        assertThat(thin.get("alerts").toString()).contains("CRAR");
        assertThat(CapitalPlanner.validate(new CapitalPlanner.Inputs(0, 0, 0, 0, 0, 0, 2, 20, 11, 2, 4, 0, 0, 0, 0.15, 1, 0))).hasSize(4);
    }
}
