package com.rhythm.los.policy;

import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.portfolio.SyntheticBook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyStudioTest {

    @Test
    void syntheticBookIsDeterministicAndPlausible() {
        List<SyntheticBook.Loan> a = SyntheticBook.generate(2000, 7), b = SyntheticBook.generate(2000, 7);
        assertThat(a).hasSize(2000);
        assertThat(a.get(123).input()).isEqualTo(b.get(123).input());
        double bad = a.stream().filter(SyntheticBook.Loan::bad).count() / 2000.0;
        assertThat(bad).isBetween(0.03, 0.12);
        assertThat(a.stream().map(l -> l.input().segment()).distinct()).containsExactlyInAnyOrder("SALARIED", "MSME", "MICROFINANCE");
    }

    @Test
    void engineSeparatesGoodFromBadOnTheBook() {
        // the illustrative engine should already rank risk better than chance on the synthetic book
        List<SyntheticBook.Loan> book = SyntheticBook.generate(2000, 11);
        double badPd = 0, goodPd = 0;
        int nb = 0, ng = 0;
        for (SyntheticBook.Loan l : book) {
            EngineResult r = UnderwritingEngine.underwrite(l.input(), PolicyParams.defaults());
            if (l.bad()) { badPd += r.pd(); nb++; } else { goodPd += r.pd(); ng++; }
        }
        assertThat(badPd / nb).isGreaterThan(1.3 * goodPd / ng);
    }

    @Test
    void validationCatchesRangesAndContradictions() {
        assertThat(PolicyFields.validate(PolicyParams.defaults())).isEmpty();
        PolicyParams p = PolicyStudioService.withVersion(PolicyParams.defaults(), "X");
        var m = PolicyFields.asMap(p);
        m.put("approvePd", 0.2);
        m.put("minAge", 10);
        PolicyParams bad = com.rhythm.los.common.Json.MAPPER.convertValue(m, PolicyParams.class);
        List<String> e = PolicyFields.validate(bad);
        assertThat(e).anyMatch(x -> x.contains("Auto-approve PD must be below"));
        assertThat(e).anyMatch(x -> x.contains("Minimum age must be between"));
        assertThat(PolicyFields.diff(PolicyParams.defaults(), bad)).extracting(x -> x.get("key")).containsExactlyInAnyOrder("approvePd", "minAge");
    }

    @Test
    void stricterAutoApprovalReducesApprovalsAndBadRate() {
        var book = SyntheticBook.generate(2000, 3);
        var live = PolicyParams.defaults();
        var m = PolicyFields.asMap(live);
        m.put("approvePd", 0.03);
        PolicyParams strict = com.rhythm.los.common.Json.MAPPER.convertValue(m, PolicyParams.class);
        PolicyStudioService.Agg a = new PolicyStudioService.Agg(), b = new PolicyStudioService.Agg();
        for (var l : book) {
            a.add(UnderwritingEngine.underwrite(l.input(), live), l.input(), live, l.bad());
            b.add(UnderwritingEngine.underwrite(l.input(), strict), l.input(), strict, l.bad());
        }
        assertThat((double) b.view().get("approvalRate")).isLessThan((double) a.view().get("approvalRate"));
        assertThat((double) b.view().get("badRateApproved")).isLessThanOrEqualTo((double) a.view().get("badRateApproved"));
    }
}
