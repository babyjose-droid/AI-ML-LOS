package com.rhythm.los.engine;

import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.sanction.Kfs;
import com.rhythm.los.sanction.KfsCalculator;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Trade-finance limits: interest service on the full limit and the 25% turnover cap, instead of an EMI on the whole amount. */
class RevolvingLimitTest {
    static List<Double> twelve(double v) { return new ArrayList<>(Collections.nCopies(12, v)); }

    static EngineInput msme(double limit, boolean revolving) {
        return new EngineInput("APP-T1", "Trade Borrower", "MSME", 44, 9, new EngineInput.Kyc(0.97, 1, 1, 0.9, 1), 400000, 60000,
                new EngineInput.Bank(twelve(400000), twelve(340000), 90000, 0, 0),
                null, new EngineInput.Gst(0.95, 0.08, 0.05), null, false, false, new EngineInput.Fraud(0, false, 1), new EngineInput.Loan(limit, 12, revolving), null);
    }

    @Test
    void revolvingLimitIsServicedByInterestNotAnEmi() {
        EngineResult term = UnderwritingEngine.underwrite(msme(1_000_000, false), PolicyParams.defaults());
        EngineResult rev = UnderwritingEngine.underwrite(msme(1_000_000, true), PolicyParams.defaults());
        assertThat(rev.reqEmi()).isLessThan(term.reqEmi() / 5);
        assertThat(rev.rules()).anyMatch(r -> r.id().equals("R24") && r.pass());
        assertThat(rev.conditions()).anyMatch(c -> c.contains("drawdown"));
    }

    @Test
    void limitAboveTurnoverCapIsCountered() {
        EngineResult rev = UnderwritingEngine.underwrite(msme(2_000_000, true), PolicyParams.defaults());
        assertThat(rev.rules()).anyMatch(r -> r.id().equals("R24") && !r.pass());
        assertThat(rev.recAmt()).isEqualTo(1_200_000);
    }

    @Test
    void revolvingKfsIsInterestOnlyWithPrincipalAtExpiry() {
        Kfs k = KfsCalculator.build("APP-T1", "Trade Borrower", "Invoice finance", 1_000_000, 14, 12,
                new KfsCalculator.Charges(0.5, 2500, 0, 500, 24, "REVOLVING"));
        assertThat(k.emi()).isEqualTo(11666.67);
        assertThat(k.schedule()).hasSize(12);
        assertThat(k.schedule().get(11).principal()).isEqualTo(1_000_000);
        assertThat(k.aprPa()).isGreaterThan(14).isLessThan(15);
        assertThat(k.repaymentMode()).contains("Revolving");
    }
}
