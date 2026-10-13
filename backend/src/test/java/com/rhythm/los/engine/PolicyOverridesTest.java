package com.rhythm.los.engine;

import com.rhythm.los.policy.PolicyParams;
import com.rhythm.los.policy.PolicyService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyOverridesTest {
    static final String OV = "{\"segment:MSME\":{\"maxFoir\":0.45,\"approvePd\":0.05},\"product:BL-UNS\":{\"maxFoir\":0.40}}";

    @Test
    void noOverrideKeepsTheBasePolicy() {
        PolicyParams base = PolicyParams.defaults();
        assertThat(PolicyService.apply(base, OV, "PL", "SALARIED")).isSameAs(base);
        assertThat(PolicyService.apply(base, null, "BL-UNS", "MSME")).isSameAs(base);
    }

    @Test
    void segmentThenProductOverridesApply() {
        PolicyParams base = PolicyParams.defaults();
        PolicyParams seg = PolicyService.apply(base, OV, "GL", "MSME");
        assertThat(seg.maxFoir()).isEqualTo(0.45);
        assertThat(seg.approvePd()).isEqualTo(0.05);
        assertThat(seg.version()).isEqualTo(base.version() + "+MSME");
        PolicyParams both = PolicyService.apply(base, OV, "BL-UNS", "MSME");
        assertThat(both.maxFoir()).isEqualTo(0.40);          // product wins over segment
        assertThat(both.approvePd()).isEqualTo(0.05);        // segment value kept where the product says nothing
        assertThat(both.referPd()).isEqualTo(base.referPd()); // everything else from the base
        assertThat(both.version()).hasSizeLessThanOrEqualTo(40);
    }
}
