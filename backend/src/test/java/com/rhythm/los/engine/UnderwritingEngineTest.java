package com.rhythm.los.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.rhythm.los.common.Json;
import com.rhythm.los.decision.engine.EngineInput;
import com.rhythm.los.decision.engine.EngineResult;
import com.rhythm.los.decision.engine.UnderwritingEngine;
import com.rhythm.los.policy.PolicyParams;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden test: the Java engine must give exactly the results of the approved prototype engine
 * (golden.json was generated from the prototype's JavaScript implementation).
 */
class UnderwritingEngineTest {

    static List<Double> l(double... d) {
        List<Double> out = new ArrayList<>();
        for (double x : d) out.add(x);
        return out;
    }

    static Map<String, EngineInput> samples() {
        Map<String, EngineInput> m = new LinkedHashMap<>();
        m.put("APP-100231", new EngineInput("APP-100231", "Ramesh Patil", "MSME", 41, 8, new EngineInput.Kyc(0.97, 1, 1, 0.9, 1), 62000, 21000,
                new EngineInput.Bank(l(54e3, 58e3, 61e3, 57e3, 66e3, 72e3, 59e3, 55e3, 63e3, 60e3, 68e3, 64e3), l(50e3, 52e3, 57e3, 55e3, 58e3, 64e3, 61e3, 51e3, 56e3, 55e3, 60e3, 58e3), 21000, 1, 4500),
                null, new EngineInput.Gst(0.92, 0.12, 0.08), null, false, false, new EngineInput.Fraud(0, false, 1), new EngineInput.Loan(300000, 24)));
        m.put("APP-100232", new EngineInput("APP-100232", "Priya Nair", "SALARIED", 34, 6, new EngineInput.Kyc(1, 1, 1, 0.95, 1), 72000, 30000,
                new EngineInput.Bank(l(72e3, 72e3, 74e3, 72e3, 72e3, 85e3, 72e3, 72e3, 72e3, 74e3, 72e3, 72e3), l(66e3, 64e3, 70e3, 65e3, 63e3, 79e3, 66e3, 67e3, 64e3, 69e3, 65e3, 66e3), 26000, 0, 10000),
                new EngineInput.Bureau(742, 3, 0, false, 4, 62, 10000), null, null, false, false, new EngineInput.Fraud(0, false, 1), new EngineInput.Loan(500000, 24)));
        m.put("APP-100233", new EngineInput("APP-100233", "Lakshmi Devi", "MICROFINANCE", 36, 5, new EngineInput.Kyc(0.95, 1, 1, 0.85, 1), 22000, 9000,
                new EngineInput.Bank(l(15e3, 14e3, 21e3, 26e3, 27e3, 23e3, 16e3, 13e3, 19e3, 24e3, 26e3, 21e3), l(15e3, 16e3, 18e3, 20e3, 21e3, 20e3, 17e3, 17e3, 17e3, 18e3, 20e3, 19e3), 5200, 0, 1800),
                null, null, new EngineInput.Internal(2, 0), true, false, new EngineInput.Fraud(0, false, 1), new EngineInput.Loan(50000, 18)));
        m.put("APP-100234", new EngineInput("APP-100234", "Suresh Yadav", "MSME", 45, 4, new EngineInput.Kyc(0.96, 1, 1, 0.8, 1), 95000, 38000,
                new EngineInput.Bank(l(92e3, 78e3, 98e3, 74e3, 88e3, 104e3, 70e3, 92e3, 80e3, 97e3, 76e3, 86e3), l(88e3, 80e3, 90e3, 76e3, 84e3, 95e3, 73e3, 87e3, 79e3, 90e3, 74e3, 82e3), 14000, 1, 14000),
                new EngineInput.Bureau(702, 5, 30, false, 7, 84, 16500), new EngineInput.Gst(0.75, -0.05, 0.18), null, false, true, new EngineInput.Fraud(0, false, 2), new EngineInput.Loan(600000, 36)));
        m.put("APP-100235", new EngineInput("APP-100235", "Anil Kumar", "SALARIED", 29, 1, new EngineInput.Kyc(0.82, 1, 1, 0.55, 0.6), 95000, 18000,
                new EngineInput.Bank(l(38e3, 37e3, 40e3, 38e3, 36e3, 41e3, 39e3, 38e3), l(36e3, 37e3, 39e3, 37e3, 35e3, 40e3, 38e3, 37e3), 3500, 0, 0),
                new EngineInput.Bureau(705, 1, 0, false, 5, 14, 2500), null, null, false, false, new EngineInput.Fraud(3, true, 4), new EngineInput.Loan(400000, 36)));
        return m;
    }

    @Test
    void matchesPrototypeEngineExactly() throws Exception {
        JsonNode golden;
        try (InputStream in = getClass().getResourceAsStream("/golden.json")) {
            golden = Json.MAPPER.readTree(in);
        }
        Map<String, EngineInput> samples = samples();
        assertThat(golden.size()).isEqualTo(5);
        for (JsonNode g : golden) {
            EngineResult r = UnderwritingEngine.underwrite(samples.get(g.get("id").asText()), PolicyParams.defaults());
            String id = g.get("id").asText();
            assertThat(r.decision()).as(id).isEqualTo(g.get("decision").asText().replace(' ', '_'));
            assertThat(r.pd()).as(id + " pd").isCloseTo(g.get("pd").asDouble(), org.assertj.core.data.Offset.offset(1e-6));
            assertThat(r.score()).as(id).isEqualTo(g.get("score").asInt());
            assertThat(r.band()).as(id).isEqualTo(g.get("band").asText());
            assertThat(r.fraud()).as(id).isEqualTo(g.get("fraud").asInt());
            assertThat(r.kycStatus()).as(id).isEqualTo(g.get("kyc").asText());
            assertThat(r.income()).as(id).isEqualTo(g.get("income").asDouble());
            assertThat(r.rate()).as(id).isEqualTo(g.get("rate").asDouble());
            assertThat(r.maxEmi()).as(id).isEqualTo(g.get("maxEmi").asDouble());
            assertThat(r.reqEmi()).as(id).isEqualTo(g.get("reqEmi").asDouble());
            assertThat(r.recAmt()).as(id).isEqualTo(g.get("recAmt").asDouble());
            assertThat(r.recEmi()).as(id).isEqualTo(g.get("recEmi").asDouble());
            List<String> failed = r.rules().stream().filter(x -> !x.pass()).map(EngineResult.RuleResult::id).toList();
            List<String> expected = new ArrayList<>();
            g.get("rules").forEach(x -> expected.add(x.asText()));
            assertThat(failed).as(id + " failed rules").containsExactlyElementsOf(expected);
            Set<String> reasons = new HashSet<>();
            g.get("reasons").forEach(x -> reasons.add(x.asText()));
            assertThat(new HashSet<>(r.reasonCodes())).as(id + " reasons").isEqualTo(reasons);
        }
    }

    @Test
    void contributionsAddUpToTheModelOutput() {
        EngineResult r = UnderwritingEngine.underwrite(samples().get("APP-100234"), PolicyParams.defaults());
        double z = r.intercept() + r.contributions().stream().mapToDouble(EngineResult.Contribution::value).sum();
        assertThat(1 / (1 + Math.exp(-z))).isCloseTo(r.pd(), org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void sameInputSameResult() {
        EngineInput in = samples().get("APP-100231");
        assertThat(UnderwritingEngine.underwrite(in, PolicyParams.defaults()))
                .isEqualTo(UnderwritingEngine.underwrite(in, PolicyParams.defaults()));
    }

    @Test
    void scoreIsMonotonicInPd() {
        int prev = 901;
        for (double pd = 0.002; pd < 0.6; pd += 0.002) {
            int s = UnderwritingEngine.pdToScore(pd);
            assertThat(s).isLessThanOrEqualTo(prev);
            prev = s;
        }
        assertThat(UnderwritingEngine.band(0.005)).isEqualTo("A");
        assertThat(UnderwritingEngine.band(0.2)).isEqualTo("E");
    }

    @Test
    void tighterPolicyNeverApprovesMore() {
        PolicyParams base = PolicyParams.defaults();
        PolicyParams tight = new PolicyParams("T", 21, 65, 0.6, 0.8, 35, 70, 0.4, 0.6, 0.03, 0.10, 4, 60, 6,
                13, 3, 26, 0.65, 0.35, 1_500_000, 300_000);
        for (EngineInput in : samples().values()) {
            EngineResult a = UnderwritingEngine.underwrite(in, base);
            EngineResult b = UnderwritingEngine.underwrite(in, tight);
            if (b.decision().startsWith("APPROVE")) assertThat(a.decision()).startsWith("APPROVE");
            assertThat(b.recAmt()).isLessThanOrEqualTo(a.recAmt());
        }
    }
}
