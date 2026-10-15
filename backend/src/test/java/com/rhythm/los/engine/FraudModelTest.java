package com.rhythm.los.engine;

import com.rhythm.los.decision.engine.*;
import com.rhythm.los.policy.PolicyParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudModelTest {
    static EngineInput input(int dup, boolean tamper) {
        return new EngineInput("T-1", "Test Person", "SALARIED", 35, 5, new EngineInput.Kyc(1, 1, 1, 1, 1), 50_000, 15_000,
                new EngineInput.Bank(List.of(50_000.0, 52_000.0, 49_000.0, 51_000.0, 50_000.0, 50_500.0), List.of(40_000.0, 41_000.0, 39_000.0, 40_000.0, 40_500.0, 40_000.0),
                        20_000, 0, 0), new EngineInput.Bureau(760, 2, 0, false, 1, 60, 5_000), null, null, false, false,
                new EngineInput.Fraud(dup, tamper, 1), new EngineInput.Loan(300_000, 36), null);
    }

    @Test
    void builtInScoreIsUnchangedAndATrainedModelReplacesIt() {
        PolicyParams p = PolicyParams.defaults();
        EngineResult clean = UnderwritingEngine.underwrite(input(0, false), p);
        EngineResult tampered = UnderwritingEngine.underwrite(input(0, true), p);
        assertThat(tampered.fraud()).isGreaterThan(clean.fraud());
        assertThat(UnderwritingEngine.underwrite(input(0, false), p, null, null).fraud()).isEqualTo(clean.fraud());

        // a trained model that weighs shared links heavily and tampering not at all
        PdModel m = new PdModel("RHY-FRAUD-TRAINED", "1.1", -3.0, List.of(new PdModel.Term("dupLinks", 2.0, 0.1), new PdModel.Term("docTamper", 0.0, 0.05)));
        EngineResult links = UnderwritingEngine.underwrite(input(2, false), p, null, m);
        assertThat(links.fraud()).isEqualTo((int) Math.round(100 / (1 + Math.exp(-(-3.0 + 2.0 * (2 - 0.1))))));
        assertThat(UnderwritingEngine.underwrite(input(0, true), p, null, m).fraud()).isLessThan(tampered.fraud());
        assertThat(links.steps().toString()).contains("RHY-FRAUD-TRAINED 1.1");

        assertThat(FraudFeatures.of(input(1, true)).get("dupLinks")).isEqualTo(1.0);
        assertThat(FraudFeatures.of(input(1, true)).get("docTamper")).isEqualTo(1.0);
        assertThat(FraudFeatures.builtInProbability(input(0, true))).isGreaterThan(FraudFeatures.builtInProbability(input(0, false)));
    }
}
