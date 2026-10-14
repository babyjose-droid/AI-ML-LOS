package com.rhythm.los.engine;

import com.rhythm.los.model.Drift;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DriftTest {
    static List<Double> normal(double mean, double sd, int n, long seed) {
        Random r = new Random(seed);
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(mean + sd * r.nextGaussian());
        return out;
    }

    @Test
    void sameDistributionIsStableAndAShiftIsDrift() {
        Drift.Baseline b = Drift.baseline(normal(0, 1, 2000, 1));
        assertThat(b.expected()).hasSize(b.edges().size() + 1);
        assertThat(b.expected().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        Drift.Psi same = Drift.psi("x", "X", b, normal(0, 1, 1000, 2));
        assertThat(same.status()).isEqualTo("STABLE");
        assertThat(same.psi()).isLessThan(0.1);
        Drift.Psi shifted = Drift.psi("x", "X", b, normal(1.0, 1, 1000, 3));
        assertThat(shifted.status()).isEqualTo("DRIFT");
        assertThat(Drift.psi("x", "X", b, normal(0, 1, 10, 4)).status()).isEqualTo("TOO_FEW");
    }

    @Test
    void constantFeatureHasOneBin() {
        Drift.Baseline b = Drift.baseline(List.of(0.0, 0.0, 0.0, 0.0));
        Drift.Psi p = Drift.psi("ever90", "Ever 90", b, List.of(0.0, 0.0));
        assertThat(p.psi()).isEqualTo(0.0);
    }
}
