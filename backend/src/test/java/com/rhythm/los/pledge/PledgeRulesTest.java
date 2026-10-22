package com.rhythm.los.pledge;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class PledgeRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    static final List<PledgeRules.Holding> H = List.of(
            new PledgeRules.Holding("INE0DM101015", "Western Ghats Power", 400, 1240.0, 0.50, true, "EQUITY", TODAY),
            new PledgeRules.Holding("INE0DM201013", "Konkan Pharma", 600, 860.5, 0.50, true, "EQUITY", TODAY),
            new PledgeRules.Holding("INF0DM501018", "Sahyadri Bluechip Fund", 3250.5, 152.34, 0.50, true, "EQUITY_MF", TODAY),
            new PledgeRules.Holding("INE0DM401019", "Satpuda Mining", 5000, 38.10, 0.0, false, "EQUITY", TODAY));

    static PledgeRules.Facts sec(double loan, String lien, List<PledgeRules.Holding> h) {
        return new PledgeRules.Facts("SECURITIES", "Ashok Naik", "Ashok Naik", h, "CDSL", "1208160012345678", null, null, null, null, null, null, lien, loan, 12);
    }
    static PledgeRules.Check check(PledgeRules.Result r, String id) { return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void drawingPowerCountsOnlyApprovedSecuritiesAtTheirLtv() {
        var r = PledgeRules.evaluate(sec(700_000, "NONE", H), null, TODAY);
        // (400×1240 + 600×860.5 + 3250.5×152.34) × 50% = 753,740.59
        assertThat(r.drawingPower()).isEqualTo(753_740.59);
        assertThat(r.readyForSanction()).isTrue();
        assertThat(r.readyForDisbursement()).isFalse();
        assertThat(check(r, "PL-02").reason()).contains("Satpuda Mining");
        var marked = PledgeRules.evaluate(sec(700_000, "MARKED", H), null, TODAY);
        assertThat(marked.status()).isEqualTo("PASS");
        assertThat(marked.readyForDisbursement()).isTrue();
    }

    @Test
    void aboveDrawingPowerFailsAndConcentrationRefers() {
        assertThat(check(PledgeRules.evaluate(sec(900_000, "NONE", H), null, TODAY), "PL-03").result()).isEqualTo("FAIL");
        var one = PledgeRules.evaluate(sec(200_000, "NONE", List.of(H.get(0))), null, TODAY);
        assertThat(check(one, "PL-04").result()).isEqualTo("REFER");
        assertThat(one.readyForSanction()).isFalse();
    }

    @Test
    void stalePricesWaitForThePriceFile() {
        var old = List.of(new PledgeRules.Holding("INE0DM101015", "Western Ghats Power", 400, 1240.0, 0.50, true, "EQUITY", TODAY.minusDays(5)),
                new PledgeRules.Holding("INE0DM201013", "Konkan Pharma", 600, 860.5, 0.50, true, "EQUITY", TODAY));
        assertThat(check(PledgeRules.evaluate(sec(100_000, "MARKED", old), null, TODAY), "PL-05").result()).isEqualTo("PENDING");
    }

    @Test
    void depositLoanWithin90PercentAndMaturity() {
        var f = new PledgeRules.Facts("DEPOSIT", "Kavita Rane", "Kavita Rane", List.of(), null, null, "Fictional Co-operative Bank Ltd", "FCB/FD/2026/88123",
                500_000d, 7.25, LocalDate.of(2026, 6, 15), LocalDate.of(2029, 6, 15), "NONE", 450_000, 24);
        var r = PledgeRules.evaluate(f, null, TODAY);
        assertThat(r.value()).isGreaterThan(512_000).isLessThan(514_000);
        assertThat(check(r, "FD-03").result()).isEqualTo("PASS");
        assertThat(r.readyForSanction()).isTrue();
        var late = new PledgeRules.Facts("DEPOSIT", "Kavita Rane", "Kavita Rane", List.of(), null, null, "Bank", "X", 500_000d, 7.25, LocalDate.of(2026, 6, 15),
                LocalDate.of(2027, 6, 15), "NONE", 480_000, 24);
        var rl = PledgeRules.evaluate(late, null, TODAY);
        assertThat(check(rl, "FD-04").result()).isEqualTo("REFER");
        assertThat(check(rl, "FD-03").result()).isEqualTo("FAIL");
    }

    @Test
    void marginShortfall() {
        assertThat(PledgeRules.margin(700_000, 640_000)).isEqualTo(60_000);
        assertThat(PledgeRules.margin(600_000, 640_000)).isZero();
    }
}
