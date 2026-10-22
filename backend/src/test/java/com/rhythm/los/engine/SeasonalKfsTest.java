package com.rhythm.los.engine;

import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.KfsCalculator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Farm loans: half-yearly instalments after harvest (equal principal + half-year's interest on the balance). */
class SeasonalKfsTest {
    @Test
    void halfYearlyInstalmentsAfterHarvest() {
        Kfs k = KfsCalculator.build("APP-A1", "Farmer", "Farm loan", 240_000, 12, 24, new KfsCalculator.Charges(0.5, 500, 0, 300, 24, "SEASONAL"));
        assertThat(k.schedule()).hasSize(4);
        assertThat(k.schedule().get(0).month()).isEqualTo(6);
        assertThat(k.schedule().get(0).principal()).isEqualTo(60_000);
        assertThat(k.schedule().get(0).interest()).isEqualTo(14_400);
        assertThat(k.schedule().get(3).balance()).isZero();
        assertThat(k.emi()).isEqualTo(74_400);
        assertThat(k.aprPa()).isGreaterThan(11.5).isLessThan(13);
        assertThat(k.repaymentMode()).contains("Half-yearly");
    }
}
