package com.rhythm.los.collateral;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralChangeRulesTest {
    static final CollateralChangeRules.Item HOUSE = new CollateralChangeRules.Item(1, "COL-1", 6_000_000, 0.65);
    static final CollateralChangeRules.Item SHOP = new CollateralChangeRules.Item(2, "COL-2", 2_000_000, 0.55);

    @Test
    void closedLoanReleasesEverything() {
        assertThat(CollateralChangeRules.release(0, true, List.of(HOUSE, SHOP), 1).ok()).isTrue();
        assertThat(CollateralChangeRules.release(0, false, List.of(HOUSE), 1).ok()).isTrue();
        assertThat(CollateralChangeRules.release(0, true, List.of(HOUSE), 9).ok()).isFalse();
    }

    @Test
    void partReleaseOnlyWhenTheRestStillCovers() {
        // 30 lakh outstanding: the house alone covers 39 lakh at 65%, so the shop can go
        CollateralChangeRules.Result shopGoes = CollateralChangeRules.release(3_000_000, false, List.of(HOUSE, SHOP), 2);
        assertThat(shopGoes.ok()).isTrue();
        assertThat(shopGoes.ltvAfter()).isEqualTo(0.5);
        // the house cannot go: the shop covers only 11 lakh
        CollateralChangeRules.Result houseGoes = CollateralChangeRules.release(3_000_000, false, List.of(HOUSE, SHOP), 1);
        assertThat(houseGoes.ok()).isFalse();
        assertThat(houseGoes.shortfall()).isEqualTo(1_900_000);
        assertThat(houseGoes.reason()).contains("more must be repaid first");
        // the only collateral is released only at closure
        assertThat(CollateralChangeRules.release(100_000, false, List.of(HOUSE), 1).reason()).contains("only collateral");
    }

    @Test
    void substituteMustCarryTheLoan() {
        CollateralChangeRules.Item flat = new CollateralChangeRules.Item(3, "COL-3", 5_000_000, 0.65);
        CollateralChangeRules.Item plot = new CollateralChangeRules.Item(4, "COL-4", 3_000_000, 0.50);
        assertThat(CollateralChangeRules.substitute(3_000_000, List.of(HOUSE), 1, flat).ok()).isTrue();
        CollateralChangeRules.Result weak = CollateralChangeRules.substitute(3_000_000, List.of(HOUSE), 1, plot);
        assertThat(weak.ok()).isFalse();
        assertThat(weak.lendableAfter()).isEqualTo(1_500_000);
        assertThat(CollateralChangeRules.substitute(3_000_000, List.of(HOUSE), 1, new CollateralChangeRules.Item(5, "COL-5", 0, 0.65)).reason()).contains("approved valuation");
    }

    @Test
    void documentsAreDueThirtyDaysAfterClosureAndLateness_costs5000aDay() {
        LocalDate closed = LocalDate.of(2026, 9, 1);
        LocalDate due = CollateralChangeRules.releaseDue(closed);
        assertThat(due).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(CollateralChangeRules.compensation(due, LocalDate.of(2026, 9, 20))).isZero();
        assertThat(CollateralChangeRules.daysLate(due, LocalDate.of(2026, 10, 5))).isEqualTo(4);
        assertThat(CollateralChangeRules.compensation(due, LocalDate.of(2026, 10, 5))).isEqualTo(20_000);
    }
}
