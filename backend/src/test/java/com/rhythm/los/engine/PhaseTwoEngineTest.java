package com.rhythm.los.engine;

import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.risk.Scorecard;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 2: money rotation, network risk, household cash flow (microfinance cap), industry scorecards. */
class PhaseTwoEngineTest {

    static EngineInput copy(EngineInput in, String segment, EngineInput.Fraud fraud, EngineInput.Household hh) {
        return new EngineInput(in.id(), in.name(), segment, in.age(), in.vintage(), in.kyc(), in.declaredIncome(), in.essential(),
                in.bank(), in.bureau(), in.gst(), in.internal(), in.jlg(), in.secured(), fraud, in.loan(), in.eligibility(), hh);
    }

    static EngineInput base() { return UnderwritingEngineTest.samples().get("APP-100232"); }

    @Test
    void moneyRotationAboveThirtyPercentIsReferred() {
        EngineInput in = base();
        EngineInput.Fraud f = in.fraud();
        EngineResult low = UnderwritingEngine.underwrite(copy(in, in.segment(), new EngineInput.Fraud(f.dupLinks(), f.docTamper(), f.velocity(), 0.10, 0), null), PolicyParams.defaults());
        assertThat(low.rules()).anyMatch(r -> r.id().equals("R19") && r.pass());
        EngineResult high = UnderwritingEngine.underwrite(copy(in, in.segment(), new EngineInput.Fraud(f.dupLinks(), f.docTamper(), f.velocity(), 0.55, 0), null), PolicyParams.defaults());
        assertThat(high.rules()).anyMatch(r -> r.id().equals("R19") && !r.pass() && "REFER".equals(r.action()));
        assertThat(high.reasonCodes()).contains("RC-023");
        assertThat(high.fraud()).isGreaterThan(low.fraud());
        // without a statement (AA data) there is no rotation rule at all
        assertThat(UnderwritingEngine.underwrite(in, PolicyParams.defaults()).rules()).noneMatch(r -> r.id().equals("R19"));
    }

    @Test
    void highNetworkRiskIsReferredAndRaisesTheFraudScore() {
        EngineInput in = base();
        EngineInput.Fraud f = in.fraud();
        EngineResult r = UnderwritingEngine.underwrite(copy(in, in.segment(), new EngineInput.Fraud(f.dupLinks(), f.docTamper(), f.velocity(), 0, 70), null), PolicyParams.defaults());
        assertThat(r.rules()).anyMatch(x -> x.id().equals("R22") && !x.pass());
        assertThat(r.reasonCodes()).contains("RC-024");
        assertThat(r.fraud()).isGreaterThan(UnderwritingEngine.underwrite(in, PolicyParams.defaults()).fraud());
    }

    @Test
    void microfinanceUsesTheHouseholdAndTheFiftyPercentCap() {
        EngineInput in = base();
        // ₹20,000 a month household income, ₹6,000 already repaid: room for ₹4,000 a month under the 50% cap
        EngineInput.Household hh = new EngineInput.Household(5, 2, 20000, 9000, 6000);
        EngineResult r = UnderwritingEngine.underwrite(copy(in, "MICROFINANCE", in.fraud(), hh), PolicyParams.defaults());
        assertThat(r.income()).isEqualTo(20000);
        assertThat(r.maxEmi()).isLessThanOrEqualTo(4000);
        assertThat(r.rules()).anyMatch(x -> x.id().equals("R20") && x.pass());
        assertThat(r.rules()).anyMatch(x -> x.id().equals("R21"));
        assertThat(r.steps()).anyMatch(s -> s.name().equals("Household cash flow"));
        // above ₹3 lakh a year the loan is not a microfinance loan
        EngineResult rich = UnderwritingEngine.underwrite(copy(in, "MICROFINANCE", in.fraud(), new EngineInput.Household(4, 2, 40000, 15000, 0)), PolicyParams.defaults());
        assertThat(rich.rules()).anyMatch(x -> x.id().equals("R20") && !x.pass());
        assertThat(rich.decision()).isEqualTo("REJECT");
        // other segments: the household is shown but does not replace the bank income
        EngineResult msme = UnderwritingEngine.underwrite(copy(in, in.segment(), in.fraud(), hh), PolicyParams.defaults());
        assertThat(msme.rules()).noneMatch(x -> x.id().equals("R20"));
    }

    @Test
    void industryScorecardPointsAndOdds() {
        Scorecard.Definition kirana = Scorecard.builtIns().stream().filter(d -> d.industry().equals("KIRANA")).findFirst().orElseThrow();
        EngineInput in = base();
        EngineResult r = UnderwritingEngine.underwrite(in, PolicyParams.defaults());
        Map<String, Double> field = new HashMap<>(Map.of("stockValue", 2.5 * in.loan().amount(), "shopSizeSqft", 250.0));
        Scorecard.Result good = Scorecard.score(kirana, Scorecard.values(in, r, field));
        Scorecard.Result noField = Scorecard.score(kirana, Scorecard.values(in, r, null));
        assertThat(good.score()).isGreaterThan(noField.score());
        assertThat(noField.missing()).contains("Stock value / loan", "Shop size (sq ft)");
        assertThat(good.pd()).isLessThan(noField.pd());
        // base points give the base odds: PD = 1 / (1 + 19) = 5%
        Scorecard.Definition flat = new Scorecard.Definition("T", "T", "GENERAL", 600, 19, 40, java.util.List.of());
        assertThat(Scorecard.score(flat, Map.of()).pd()).isEqualTo(0.05);
    }
}
