package com.rhythm.los.engine;

import com.rhythm.los.decision.Delegation;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.ReasonCodes;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.policy.PolicyParams;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Corrections from the review against the prototype and BRD/FSD (13 Oct 2026). */
class CorrectionsTest {

    static EngineInput with(EngineInput in, EngineInput.Eligibility el) {
        return new EngineInput(in.id(), in.name(), in.segment(), in.age(), in.vintage(), in.kyc(), in.declaredIncome(), in.essential(),
                in.bank(), in.bureau(), in.gst(), in.internal(), in.jlg(), in.secured(), in.fraud(), in.loan(), el);
    }

    static EngineInput.Eligibility product(int minAge, int maxAge, double minVintage, double minIncome, double rateMin, double rateMax) {
        return new EngineInput.Eligibility("TEST", minAge, maxAge, minVintage, minIncome, null, rateMin, rateMax);
    }

    @Test
    void priceStaysInsideTheProductRateBand() {
        for (EngineInput in : UnderwritingEngineTest.samples().values()) {
            EngineResult r = UnderwritingEngine.underwrite(with(in, product(18, 80, 0, 0, 14, 15)), PolicyParams.defaults());
            assertThat(r.rate()).isBetween(14.0, 15.0);
        }
    }

    @Test
    void withoutAProductTheEngineIsUnchanged() {
        for (EngineInput in : UnderwritingEngineTest.samples().values()) {
            EngineResult a = UnderwritingEngine.underwrite(in, PolicyParams.defaults());
            EngineResult b = UnderwritingEngine.underwrite(with(in, null), PolicyParams.defaults());
            assertThat(b.decision()).isEqualTo(a.decision());
            assertThat(b.rate()).isEqualTo(a.rate());
        }
    }

    @Test
    void productEligibilityRejectsOutsideItsLimits() {
        EngineInput priya = UnderwritingEngineTest.samples().get("APP-100232"); // 34 years, 24 months, vintage 6
        EngineResult old = UnderwritingEngine.underwrite(with(priya, product(21, 35, 0, 0, 13, 24)), PolicyParams.defaults());
        assertThat(old.decision()).isEqualTo("REJECT");
        assertThat(old.rules()).anyMatch(x -> x.id().equals("R01") && !x.pass());
        assertThat(old.reasonCodes()).contains("RC-022");
        EngineResult vintage = UnderwritingEngine.underwrite(with(priya, product(21, 58, 8, 0, 13, 24)), PolicyParams.defaults());
        assertThat(vintage.rules()).anyMatch(x -> x.id().equals("R16") && !x.pass());
        assertThat(vintage.decision()).isEqualTo("REJECT");
        EngineResult income = UnderwritingEngine.underwrite(with(priya, product(21, 58, 0, 200000, 13, 24)), PolicyParams.defaults());
        assertThat(income.rules()).anyMatch(x -> x.id().equals("R17") && !x.pass());
        EngineResult fine = UnderwritingEngine.underwrite(with(priya, product(21, 58, 2, 30000, 13, 24)), PolicyParams.defaults());
        assertThat(fine.rules()).noneMatch(x -> List.of("R01", "R16", "R17").contains(x.id()) && !x.pass());
    }

    @Test
    void tooLittleBankDataIsIncompleteNotReferred() {
        EngineInput in = UnderwritingEngineTest.samples().get("APP-100231");
        EngineInput.Bank b = in.bank();
        EngineInput shortBank = new EngineInput(in.id(), in.name(), in.segment(), in.age(), in.vintage(), in.kyc(), in.declaredIncome(), in.essential(),
                new EngineInput.Bank(b.inflows().subList(0, 4), b.outflows().subList(0, 4), b.avgBalance(), b.bounces(), b.detectedEmi()),
                in.bureau(), in.gst(), in.internal(), in.jlg(), in.secured(), in.fraud(), in.loan(), null);
        EngineResult r = UnderwritingEngine.underwrite(shortBank, PolicyParams.defaults());
        assertThat(r.decision()).isEqualTo("INCOMPLETE");
        assertThat(r.missingData()).singleElement().asString().contains("6 months");
        assertThat(r.reasonCodes()).contains("RC-013");
        assertThat(r.conditions()).isEmpty();
        // a gate (product age) still rejects even on partial data
        EngineResult gate = UnderwritingEngine.underwrite(with(shortBank, product(21, 30, 0, 0, 13, 26)), PolicyParams.defaults());
        assertThat(gate.decision()).isEqualTo("REJECT");
    }

    @Test
    void reasonCodesFollowTheFsd() {
        assertThat(ReasonCodes.TEXT.get("RC-020")).isEqualTo("Model or data quality error");
        assertThat(ReasonCodes.TEXT).containsKeys("RC-007", "RC-018", "RC-021", "RC-022");
    }

    @Test
    void sanctionAuthorityFollowsTheAmount() {
        assertThat(Delegation.levelForAmount(200_000)).isEqualTo("L1");
        assertThat(Delegation.levelForAmount(200_001)).isEqualTo("L2");
        assertThat(Delegation.levelForAmount(1_000_001)).isEqualTo("L3");
    }
}
