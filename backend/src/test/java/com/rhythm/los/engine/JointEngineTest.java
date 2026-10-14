package com.rhythm.los.engine;

import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.policy.PolicyParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Co-applicants' income in affordability, and the party policy rule R23. */
class JointEngineTest {

    static EngineInput with(EngineInput in, EngineInput.Joint j) {
        return new EngineInput(in.id(), in.name(), in.segment(), in.age(), in.vintage(), in.kyc(), in.declaredIncome(), in.essential(),
                in.bank(), in.bureau(), in.gst(), in.internal(), in.jlg(), in.secured(), in.fraud(), in.loan(), in.eligibility(), in.household(), j);
    }

    @Test
    void coApplicantIncomeRaisesCapacityAndTheirRepaymentsCount() {
        EngineInput in = UnderwritingEngineTest.samples().get("APP-100232");
        EngineResult alone = UnderwritingEngine.underwrite(in, PolicyParams.defaults());
        EngineResult joint = UnderwritingEngine.underwrite(with(in, new EngineInput.Joint(1, 0, 1, 40_000, 5_000, List.of())), PolicyParams.defaults());
        assertThat(joint.income()).isEqualTo(alone.income() + 40_000);
        assertThat(joint.existing()).isEqualTo(alone.existing() + 5_000);
        assertThat(joint.maxEmi()).isGreaterThan(alone.maxEmi());
        assertThat(joint.steps()).anyMatch(s -> s.name().equals("Co-applicants & guarantors") && s.value().contains("₹40,000/mo income from 1 added"));
        assertThat(joint.rules()).anyMatch(r -> r.id().equals("R23") && r.pass());
        // no parties: no step and no rule
        assertThat(alone.rules()).noneMatch(r -> r.id().equals("R23"));
        assertThat(alone.steps()).noneMatch(s -> s.name().equals("Co-applicants & guarantors"));
    }

    @Test
    void partyPolicyFailureRefersWithItsOwnReasonCode() {
        EngineInput in = UnderwritingEngineTest.samples().get("APP-100232");
        EngineResult r = UnderwritingEngine.underwrite(with(in, new EngineInput.Joint(0, 1, 0, 0, 0, List.of("Ravi (guarantor): bureau score 640 below 700"))),
                PolicyParams.defaults());
        assertThat(r.rules()).anyMatch(x -> x.id().equals("R23") && !x.pass() && "REFER".equals(x.action()) && x.description().contains("640 below 700"));
        assertThat(r.reasonCodes()).contains("RC-026");
        assertThat(r.decision()).isNotEqualTo("APPROVE");
    }
}
