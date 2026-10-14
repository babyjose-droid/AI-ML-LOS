package com.rhythm.los.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatementBankDataTest {
    @Test
    void adjustedTurnoverIsUsedAndRotationCarried() {
        String json = """
                {"bank":"HDFC","accountNumberMasked":"XXXX1234",
                 "monthly":[{"inflow":200000,"adjustedInflow":60000,"outflow":150000},{"inflow":180000,"adjustedInflow":70000,"outflow":140000}],
                 "summary":{"avgBalance":30000,"bounces":0,"detectedEmi":5000,"gamblingCount":0,"rotation":{"rotationShare":0.55}}}""";
        VendorModels.AaStatement s = DataFetchService.statementToBankData(json);
        assertThat(s.inflows()).containsExactly(60000.0, 70000.0);
        assertThat(s.rotationShare()).isEqualTo(0.55);
        assertThat(s.grossMonthlyInflow()).isEqualTo(190000.0);
        // older analyses without the adjusted figure keep the plain inflow
        VendorModels.AaStatement old = DataFetchService.statementToBankData("""
                {"monthly":[{"inflow":50000,"outflow":40000}],"summary":{"avgBalance":1000}}""");
        assertThat(old.inflows()).containsExactly(50000.0);
        assertThat(old.rotationShare()).isZero();
    }
}
