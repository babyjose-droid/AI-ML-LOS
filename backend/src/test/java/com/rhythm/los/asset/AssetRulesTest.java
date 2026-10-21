package com.rhythm.los.asset;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AssetRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    static final AssetRules.Policy CV = new AssetRules.Policy("CV", "Commercial vehicle (new)", 0.85, 0.15, 0.15, 1, true, true, "Chassis number", 0.05,
            30_000_000d, List.of("RC_HP", "INSURANCE_HP"), 30);
    static final AssetRules.DealerFacts DEALER = new AssetRules.DealerFacts(1L, "Sahyadri Commercial Motors", "ACTIVE", true, "CV", "27AAKCS4512M1Z5", 50_000_000d, 4_000_000);

    static AssetRules.Facts facts(double loan, double down, boolean verified, String gstin, String serial, Double insured) {
        return new AssetRules.Facts("CV", 2026, serial, null, 1, 2_850_000d, true, "SCM/26-27/1184", 2_410_000d, 433_800d, 6_200d, 2_850_000d,
                gstin, true, down, verified, insured, TODAY.plusYears(1), true, null, loan, 60, 13, 0, null, null,
                Map.of("invoiceNo", "SCM/26-27/1184", "total", 2_850_000, "gstin", "27AAKCS4512M1Z5"));
    }

    static AssetRules.Check check(AssetRules.Result r, String id) { return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void cleanCaseWithVerifiedDownPaymentPasses() {
        var r = AssetRules.evaluate(facts(2_400_000, 450_000, true, "27AAKCS4512M1Z5", "MAT448123S2K45678", 2_850_000d), CV, DEALER, null, TODAY);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.maxLoan()).isEqualTo(2_422_500d);
        assertThat(r.beforeDisbursement()).isEmpty();
        assertThat(check(r, "AF-08").reason()).contains("84.2%");
        assertThat(r.cover()).isNotEmpty();
        assertThat(r.cover().get(0).get("assetValue")).isEqualTo(2_850_000.0);
        assertThat(r.conditions()).anyMatch(c -> c.contains("RC within 30 days"));
    }

    @Test
    void unverifiedDownPaymentAndMissingSerialWaitForDisbursementNotSanction() {
        var r = AssetRules.evaluate(facts(2_400_000, 450_000, false, "27AAKCS4512M1Z5", null, null), CV, DEALER, null, TODAY);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.beforeDisbursement()).hasSize(3);
        assertThat(check(r, "AF-07").result()).isEqualTo("PENDING");
        assertThat(check(r, "AF-06").result()).isEqualTo("PENDING");
        assertThat(check(r, "AF-10").result()).isEqualTo("PENDING");
    }

    @Test
    void loanAboveLtvIsReferredAndCanBeApprovedAsAnException() {
        var f = facts(2_600_000, 250_000, true, "27AAKCS4512M1Z5", "MAT448123S2K45678", 2_850_000d);
        var r = AssetRules.evaluate(f, CV, DEALER, null, TODAY);
        assertThat(r.status()).isEqualTo("REFER");
        assertThat(check(r, "AF-08").result()).isEqualTo("REFER");
        assertThat(check(r, "AF-07").result()).isEqualTo("REFER");
        Map<String, Map<String, Object>> rulings = new HashMap<>();
        rulings.put("AF-07", Map.of("by", "cm1", "note", "Dealer scheme discount"));
        rulings.put("AF-08", Map.of("by", "cm1", "note", "Strong repeat fleet customer"));
        var ok = AssetRules.evaluate(f, CV, DEALER, rulings, TODAY);
        assertThat(ok.status()).isEqualTo("PASS");
        assertThat(check(ok, "AF-08").ruling()).contains("cm1");
    }

    @Test
    void wrongGstinSuspendedDealerAndDuplicatesFailAndCannotBeOverridden() {
        var bad = AssetRules.evaluate(facts(2_400_000, 450_000, true, "27AAQFQ1100D1Z1", "MAT448123S2K45678", 2_850_000d), CV, DEALER,
                Map.of("AF-04", Map.of("by", "cm1", "note", "x")), TODAY);
        assertThat(bad.status()).isEqualTo("FAIL");
        assertThat(check(bad, "AF-04").ruling()).isNull();
        var suspended = new AssetRules.DealerFacts(7L, "QuickDeal Traders", "SUSPENDED", false, "CV,CAR", "27AAQFQ1100D1Z1", null, 0);
        assertThat(AssetRules.evaluate(facts(2_400_000, 450_000, true, "27AAQFQ1100D1Z1", "MAT448123S2K45678", 2_850_000d), CV, suspended, null, TODAY).status()).isEqualTo("FAIL");
        var dupe = new AssetRules.Facts("CV", 2026, "MAT448123S2K45678", null, 1, 2_850_000d, true, "X1", 2_410_000d, 433_800d, 6_200d, 2_850_000d,
                "27AAKCS4512M1Z5", true, 450_000d, true, 2_850_000d, TODAY.plusYears(1), true, null, 2_400_000, 60, 13, 0, null, "APP-300031", null);
        assertThat(check(AssetRules.evaluate(dupe, CV, DEALER, null, TODAY), "AF-05").reason()).contains("APP-300031");
    }

    @Test
    void invoiceArithmeticQuoteVarianceAiMismatchAndFleetLimit() {
        var f = new AssetRules.Facts("CV", 2026, "MAT448123S2K45678", null, 1, 2_600_000d, true, "SCM/26-27/1184", 2_410_000d, 433_800d, 0d, 2_850_000d,
                "27AAKCS4512M1Z5", true, 450_000d, true, 2_850_000d, TODAY.plusYears(1), true, null, 2_400_000, 60, 13, 29_000_000, null, null,
                Map.of("invoiceNo", "SCM/26-27/1185", "total", 2_850_000));
        var r = AssetRules.evaluate(f, CV, DEALER, null, TODAY);
        assertThat(check(r, "AF-03").result()).isEqualTo("REFER");
        assertThat(check(r, "AF-09").reason()).contains("+9.6%");
        assertThat(check(r, "AF-13").reason()).contains("SCM/26-27/1185");
        assertThat(check(r, "AF-11").reason()).contains("fleet limit");
    }

    @Test
    void solarSubsidyReducesTheCostAndRupeesAreIndian() {
        var solar = new AssetRules.Policy("SOLAR", "Rooftop solar system", 0.90, 0.10, 0.10, 1, false, false, "Inverter serial number", 0.05, null, List.of("INSTALLATION_CERT", "NET_METER"), 45);
        var f = new AssetRules.Facts("SOLAR", 2026, "SPK5K260012", null, 1, 330_000d, true, null, null, null, null, null, null, false,
                40_000d, false, null, null, false, 78_000d, 220_000, 48, 11, 0, null, null, null);
        var r = AssetRules.evaluate(f, solar, new AssetRules.DealerFacts(4L, "SunPeak Solar Installers", "ACTIVE", true, "SOLAR", "27AAPFS9921L1Z4", null, 0), null, TODAY);
        assertThat(r.netCost()).isEqualTo(252_000.0);
        assertThat(r.maxLoan()).isEqualTo(226_800.0);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.conditions()).anyMatch(c -> c.contains("Subsidy of ₹78,000"));
        assertThat(AssetRules.rupees(12_34_567.0)).isEqualTo("₹12,34,567");
    }
}
