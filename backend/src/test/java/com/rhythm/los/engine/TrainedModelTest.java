package com.rhythm.los.engine;

import com.rhythm.los.decision.engine.*;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.portfolio.SyntheticBook;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class TrainedModelTest {

    @Test
    void featuresAreComputedForEveryApplicant() {
        for (SyntheticBook.Loan l : SyntheticBook.generate(300, 5)) {
            Map<String, Double> f = Features.of(l.input());
            assertThat(f.keySet()).containsExactlyElementsOf(Features.ALL.stream().map(Features.Def::key).toList());
            assertThat(f.values()).allMatch(v -> !v.isNaN() && !v.isInfinite());
            assertThat(f.get("stability")).isBetween(0.0, 1.0);
            assertThat(f.get("thinFile")).isIn(0.0, 1.0);
        }
    }

    @Test
    void trainedModelReplacesTheBuiltInCoefficientsAndStaysExplainable() {
        EngineInput in = SyntheticBook.generate(10, 9).get(3).input();
        Map<String, Double> f = Features.of(in);
        PdModel m = new PdModel("RHY-PD-TRAINED", "1.1", -3.0, List.of(
                new PdModel.Term("bounces", 0.5, 0.2), new PdModel.Term("stability", -2.0, 0.85), new PdModel.Term("bureauScoreGap", 0.9, 0.0)));
        EngineResult base = UnderwritingEngine.underwrite(in, PolicyParams.defaults());
        EngineResult r = UnderwritingEngine.underwrite(in, PolicyParams.defaults(), m);
        double z = -3.0 + 0.5 * (f.get("bounces") - 0.2) - 2.0 * (f.get("stability") - 0.85) + 0.9 * f.get("bureauScoreGap");
        assertThat(r.pd()).isCloseTo(1 / (1 + Math.exp(-z)), within(1e-12));
        assertThat(r.modelVersion()).isEqualTo("RHY-PD-TRAINED 1.1");
        assertThat(r.contributions()).hasSize(3).extracting(EngineResult.Contribution::label).contains("EMI/cheque bounces (12m)", "Income stability", "Bureau score");
        assertThat(r.intercept() + r.contributions().stream().mapToDouble(EngineResult.Contribution::value).sum()).isCloseTo(z, within(1e-12));
        assertThat(base.modelVersion()).startsWith("RHY-PD-");
        assertThat(base.modelVersion()).doesNotContain("TRAINED");
    }
}
