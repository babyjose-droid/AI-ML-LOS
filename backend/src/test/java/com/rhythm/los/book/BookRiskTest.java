package com.rhythm.los.book;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BookRiskTest {
    static final LocalDate ON = LocalDate.of(2026, 9, 30);

    @Test
    void smaBuckets() {
        assertThat(BookRisk.bucket(0)).isEqualTo("STANDARD");
        assertThat(BookRisk.bucket(1)).isEqualTo("SMA-0");
        assertThat(BookRisk.bucket(30)).isEqualTo("SMA-0");
        assertThat(BookRisk.bucket(31)).isEqualTo("SMA-1");
        assertThat(BookRisk.bucket(60)).isEqualTo("SMA-1");
        assertThat(BookRisk.bucket(61)).isEqualTo("SMA-2");
        assertThat(BookRisk.bucket(90)).isEqualTo("SMA-2");
        assertThat(BookRisk.bucket(91)).isEqualTo("NPA");
    }

    @Test
    void npaIsUpgradedOnlyWhenAllArrearsArePaid() {
        BookRisk.Classification npa = BookRisk.classify("SMA-2", null, 95, 30000, ON);
        assertThat(npa.cls()).isEqualTo("NPA");
        assertThat(npa.npaSince()).isEqualTo(ON.minusDays(5));
        // days past due fall but arrears remain: still NPA, NPA date kept
        BookRisk.Classification still = BookRisk.classify("NPA", npa.npaSince(), 10, 5000, ON.plusMonths(1));
        assertThat(still.cls()).isEqualTo("NPA");
        assertThat(still.npaSince()).isEqualTo(npa.npaSince());
        BookRisk.Classification cured = BookRisk.classify("NPA", npa.npaSince(), 0, 0, ON.plusMonths(2));
        assertThat(cured.cls()).isEqualTo("STANDARD");
        assertThat(cured.npaSince()).isNull();
    }

    @Test
    void minimumProvisionByAgeAndSecurity() {
        assertThat(BookRisk.provisionRate("STANDARD", null, false, ON)).isEqualTo(0.004);
        assertThat(BookRisk.provisionRate("SMA-2", null, true, ON)).isEqualTo(0.004);
        assertThat(BookRisk.provisionRate("NPA", ON.minusMonths(6), false, ON)).isEqualTo(0.10);
        assertThat(BookRisk.provisionRate("NPA", ON.minusMonths(18), false, ON)).isEqualTo(1.0);
        assertThat(BookRisk.provisionRate("NPA", ON.minusMonths(18), true, ON)).isEqualTo(0.20);
        assertThat(BookRisk.provisionRate("NPA", ON.minusMonths(36), true, ON)).isEqualTo(0.30);
        assertThat(BookRisk.provisionRate("NPA", ON.minusMonths(60), true, ON)).isEqualTo(0.50);
    }

    @Test
    void eclStages() {
        BookRisk.Ecl s1 = BookRisk.ecl(0, "STANDARD", false, 0.05, 0.6, 100000, 24);
        assertThat(s1.stage()).isEqualTo(1);
        assertThat(s1.ecl()).isEqualTo(3000);
        BookRisk.Ecl s2 = BookRisk.ecl(45, "SMA-1", false, 0.05, 0.6, 100000, 24);
        assertThat(s2.stage()).isEqualTo(2);
        assertThat(s2.pd()).isCloseTo(1 - 0.95 * 0.95, within(0.0001));
        assertThat(BookRisk.ecl(0, "STANDARD", true, 0.05, 0.6, 100000, 24).stage()).isEqualTo(2);
        BookRisk.Ecl s3 = BookRisk.ecl(120, "NPA", false, 0.05, 0.6, 100000, 24);
        assertThat(s3.stage()).isEqualTo(3);
        assertThat(s3.ecl()).isEqualTo(60000);
    }

    @Test
    void behaviourPdFollowsRepayment() {
        assertThat(BookRisk.behaviourPd(0.05, 0, 0, 12)).isCloseTo(0.04, within(1e-9));
        assertThat(BookRisk.behaviourPd(0.05, 10, 0, 12)).isEqualTo(0.1);
        assertThat(BookRisk.behaviourPd(0.05, 45, 0, 12)).isEqualTo(0.3);
        assertThat(BookRisk.behaviourPd(0.05, 75, 0, 12)).isEqualTo(0.5);
        assertThat(BookRisk.behaviourPd(0.05, 95, 0, 12)).isEqualTo(1.0);
        assertThat(BookRisk.behaviourPd(0.05, 0, 2, 2)).isCloseTo(0.075, within(1e-9));
    }

    @Test
    void earlyWarningSignalsAndAction() {
        BookRisk.Ews quiet = BookRisk.ews(0, 0, 0, 12, 12, 0.03, false, ON.minusDays(5), ON, false);
        assertThat(quiet.score()).isZero();
        assertThat(quiet.action()).isEqualTo("No action");
        BookRisk.Ews firstMiss = BookRisk.ews(15, 0, 1, 0, 1, 0.1, false, null, ON, false);
        assertThat(firstMiss.signals().stream().map(BookRisk.Signal::reason).toList()).anyMatch(r -> r.contains("First instalment"));
        assertThat(firstMiss.score()).isGreaterThanOrEqualTo(40);
        BookRisk.Ews bad = BookRisk.ews(75, 45, 3, 6, 9, 0.5, true, ON.minusDays(80), ON, true);
        assertThat(bad.score()).isEqualTo(100);
        assertThat(bad.action()).contains("Field visit");
        List<String> reasons = bad.signals().stream().map(BookRisk.Signal::reason).toList();
        assertThat(reasons).anyMatch(r -> r.contains("rising")).anyMatch(r -> r.contains("bounced")).anyMatch(r -> r.contains("Restructured"));
        assertThat(BookRisk.ews(120, 90, 0, 6, 9, 1, false, null, ON, false).action()).contains("recovery");
    }
}
