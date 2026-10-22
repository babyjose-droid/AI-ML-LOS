package com.rhythm.los.collateral;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gold LTV slabs are configurable, but never above the RBI caps by loan size. */
class GoldLtvSlabsTest {
    @Test
    void defaultSlabsFollowRbi() {
        assertThat(CollateralEngine.goldMaxLtv(200_000)).isEqualTo(0.85);
        assertThat(CollateralEngine.goldMaxLtv(400_000)).isEqualTo(0.80);
        assertThat(CollateralEngine.goldMaxLtv(900_000)).isEqualTo(0.75);
    }

    @Test
    void lenderCanSetLowerSlabs() {
        String s = "100000:0.80,500000:0.75,*:0.70";
        assertThat(CollateralEngine.goldMaxLtv(80_000, s)).isEqualTo(0.80);
        assertThat(CollateralEngine.goldMaxLtv(300_000, s)).isEqualTo(0.75);
        assertThat(CollateralEngine.goldMaxLtv(2_000_000, s)).isEqualTo(0.70);
    }

    @Test
    void slabsAboveRbiCapsOrMalformedAreRejected() {
        assertThatThrownBy(() -> CollateralEngine.goldSlabs("250000:0.90,*:0.75")).hasMessageContaining("RBI cap");
        assertThatThrownBy(() -> CollateralEngine.goldSlabs("500000:0.80,250000:0.85,*:0.75")).hasMessageContaining("increase");
        assertThatThrownBy(() -> CollateralEngine.goldSlabs("250000:0.85")).hasMessageContaining("*:");
    }
}
