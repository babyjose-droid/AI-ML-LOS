package com.rhythm.los.growth;

import com.rhythm.los.book.LiveLoan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class GrowthRulesTest {

    @Test
    void propensityFallsWithArrearsAndBrokenPromises() {
        double good = CollectionsAi.propensity(5, 0, 0.05, 12, 1, 0, 10);
        double worse = CollectionsAi.propensity(45, 2, 0.3, 12, 0, 0, 50);
        double broken = CollectionsAi.propensity(45, 2, 0.3, 12, 0, 2, 50);
        assertThat(good).isGreaterThan(0.7);
        assertThat(worse).isLessThan(good);
        assertThat(broken).isLessThan(worse);
    }

    @Test
    void channelByBucketAndContactHours() {
        assertThat(CollectionsAi.recommend(0, 45, 0.8, 0, "SALARIED", null, false).channel()).isEqualTo("WHATSAPP");
        assertThat(CollectionsAi.recommend(5, 20, 0.8, 0, "SALARIED", null, false).channel()).isEqualTo("SMS");
        assertThat(CollectionsAi.recommend(20, 50, 0.6, 0, "MSME", null, false).channel()).isEqualTo("CALL");
        assertThat(CollectionsAi.recommend(45, 70, 0.6, 1, "MSME", null, false).channel()).isEqualTo("FIELD_VISIT");
        assertThat(CollectionsAi.recommend(45, 70, 0.6, 0, "MSME", null, false).channel()).isEqualTo("CALL");
        assertThat(CollectionsAi.recommend(75, 80, 0.4, 0, "MSME", null, false).channel()).isEqualTo("FIELD_VISIT");
        assertThat(CollectionsAi.recommend(120, 100, 0.1, 0, "MSME", null, false).channel()).isEqualTo("NOTICE");
        assertThat(CollectionsAi.recommend(20, 50, 0.6, 0, "MSME", null, true).channel()).isEqualTo("WHATSAPP");
        // the 61-90 bucket is the most urgent: it is about to turn NPA
        assertThat(CollectionsAi.recommend(75, 80, 0.4, 0, "MSME", null, false).priority())
                .isGreaterThan(CollectionsAi.recommend(20, 80, 0.4, 0, "MSME", null, false).priority());
        assertThat(CollectionsAi.bestTime("SALARIED", null)).contains("6–7 pm");
        assertThat(CollectionsAi.bestTime("MSME", 15)).startsWith("15:00–16:00");
        assertThat(CollectionsAi.bestTime("MSME", 21)).contains("11 am");
        assertThat(CollectionsAi.withinContactHours(LocalTime.of(8, 0))).isTrue();
        assertThat(CollectionsAi.withinContactHours(LocalTime.of(18, 59))).isTrue();
        assertThat(CollectionsAi.withinContactHours(LocalTime.of(19, 0))).isFalse();
        assertThat(CollectionsAi.withinContactHours(LocalTime.of(7, 30))).isFalse();
    }

    static LiveLoan loan() {
        LiveLoan l = new LiveLoan();
        l.setStatus("ACTIVE");
        l.setClassification("STANDARD");
        l.setSanctionedAmount(new BigDecimal("400000"));
        l.setOutstanding(new BigDecimal("250000"));
        l.setRate(new BigDecimal("18.00"));
        l.setTenureMonths(36);
        l.setPaidEmis(12);
        l.setBehaviourPd(new BigDecimal("0.02"));
        return l;
    }

    @Test
    void topupForGoodPayersOnly() {
        TopupRules.Check ok = TopupRules.check(loan(), 12, 1_500_000);
        assertThat(ok.eligible()).isTrue();
        assertThat(ok.amount()).isEqualTo(350000);
        assertThat(ok.rate()).isEqualTo(17.5);
        LiveLoan late = loan();
        late.setMaxDpd(45);
        late.setBounces3m(1);
        TopupRules.Check no = TopupRules.check(late, 12, 1_500_000);
        assertThat(no.eligible()).isFalse();
        assertThat(no.amount()).isZero();
        assertThat(no.reasons()).anyMatch(r -> r.contains("worst 45")).anyMatch(r -> r.contains("Bounced"));
        assertThat(TopupRules.check(loan(), 3, 1_500_000).reasons()).anyMatch(r -> r.contains("months on book"));
        LiveLoan r = loan();
        r.setRestructured(true);
        assertThat(TopupRules.check(r, 12, 1_500_000).eligible()).isFalse();
        assertThat(TopupRules.check(loan(), 12, 260_000).reasons()).anyMatch(x -> x.contains("under ₹25,000"));
    }

    @Test
    void restructureScheduleCapitalisesMoratoriumInterest() {
        List<Map<String, Object>> s = RestructureService.schedule(100000, 12, 2, 14, LocalDate.of(2026, 10, 1));
        assertThat(s).hasSize(14);
        assertThat(s.get(0).get("emi")).isEqualTo(0L);
        assertThat(((Number) s.get(1).get("balance")).doubleValue()).isCloseTo(102010, within(1.0));
        assertThat(((Number) s.get(13).get("balance")).doubleValue()).isCloseTo(0, within(1.0));
        assertThat(s.get(2).get("due")).isEqualTo("2027-01-01");
    }

    @Test
    void rateRiseCutsVolumeAndRaisesYield() {
        List<RateSimulator.Loan> mix = List.of(new RateSimulator.Loan(500000, 18, 0.04, 0.65, 36), new RateSimulator.Loan(200000, 22, 0.08, 0.65, 24));
        RateSimulator.Assumptions a = new RateSimulator.Assumptions(6, 5, 11, 3, 26);
        RateSimulator.Outcome base = RateSimulator.run(mix, 0, a), up = RateSimulator.run(mix, 2, a), down = RateSimulator.run(mix, -2, a);
        assertThat(base.volume()).isEqualTo(700000);
        assertThat(up.volume()).isLessThan(base.volume());
        assertThat(down.volume()).isGreaterThan(base.volume());
        assertThat(up.yield()).isGreaterThan(base.yield());
        assertThat(up.expectedLossRate()).isGreaterThan(base.expectedLossRate());
        assertThat(base.nim()).isCloseTo(base.yield() - 11, within(0.01));
        // the 22% loan hits the 26% cap at +5
        assertThat(RateSimulator.run(mix, 5, a).capped()).isEqualTo(1);
        assertThat(RateSimulator.curve(mix, a)).hasSize(13);
    }
}
