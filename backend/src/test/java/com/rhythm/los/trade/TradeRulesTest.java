package com.rhythm.los.trade;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class TradeRulesTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    static final String SELLER = "27DESPD4512K1Z3", BUYER = "27AAWCW7731B1Z8";
    static final TradeRules.Party WAC = new TradeRules.Party(3L, "Western Auto Components Ltd", "BUYER", null, BUYER, "ACTIVE", true, 0.80, 90, 7, true, 0.70, 50_000_000d, 0);
    static final TradeRules.Party ANCHOR = new TradeRules.Party(1L, "Sahyadri Paints Ltd", "ANCHOR", "DEALER", "27AABCS8890M1Z2", "ACTIVE", true, 1.0, 60, 7, true, 1.0, 150_000_000d, 0);

    static TradeRules.LimitFacts limit(double used, Double sub, double onBuyer, int overdue) {
        return new TradeRules.LimitFacts("RECEIVABLES", "ACTIVE", TODAY.plusMonths(11), 1_000_000, used, null, SELLER, sub, onBuyer, overdue, overdue > 0 ? "DD-8001" : null);
    }

    static TradeRules.InvoiceFacts inv(String no, double total, String dup, boolean confirmed, LocalDate date, LocalDate due) {
        return new TradeRules.InvoiceFacts(no, date, due, SELLER, BUYER, total / 1.18, total - total / 1.18, total, dup, confirmed,
                Map.of("invoiceNo", no, "total", total, "sellerGstin", SELLER, "buyerGstin", BUYER, "dueDate", due.toString()));
    }

    static TradeRules.Check check(TradeRules.Result r, String id) { return r.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void cleanInvoiceWaitsForConfirmationThenPasses() {
        var pending = TradeRules.evaluate(inv("DPC/0815", 590_000, null, false, TODAY.minusDays(3), TODAY.plusDays(57)), limit(0, 700_000d, 0, 0), WAC, null, TODAY);
        assertThat(pending.status()).isEqualTo("PENDING");
        assertThat(check(pending, "TF-10").result()).isEqualTo("PENDING");
        assertThat(pending.advance()).isEqualTo(472_000);
        var ok = TradeRules.evaluate(inv("DPC/0815", 590_000, null, true, TODAY.minusDays(3), TODAY.plusDays(57)), limit(0, 700_000d, 0, 0), WAC, null, TODAY);
        assertThat(ok.status()).isEqualTo("PASS");
        assertThat(ok.checks()).allMatch(c -> "PASS".equals(c.result()));
    }

    @Test
    void duplicateInvoiceFailsAndCannotBeOverridden() {
        var r = TradeRules.evaluate(inv("DPC/0815", 590_000, "DD-8001 (APP-300031)", true, TODAY.minusDays(3), TODAY.plusDays(57)), limit(472_000, 700_000d, 472_000, 0), WAC,
                Map.of("TF-03", Map.of("by", "cm1", "note", "trying to override a duplicate")), TODAY);
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(check(r, "TF-03").ruling()).isNull();
    }

    @Test
    void aboveAvailableLimitFailsAndSublimitRefers() {
        var over = TradeRules.evaluate(inv("X1", 1_000_000, null, true, TODAY.minusDays(1), TODAY.plusDays(30)), limit(500_000, 700_000d, 0, 0), WAC, null, TODAY);
        assertThat(check(over, "TF-08").result()).isEqualTo("FAIL");
        var sub = TradeRules.evaluate(inv("X2", 300_000, null, true, TODAY.minusDays(1), TODAY.plusDays(30)), limit(100_000, 200_000d, 100_000, 0), WAC, null, TODAY);
        assertThat(check(sub, "TF-09").result()).isEqualTo("REFER");
        var ruled = TradeRules.evaluate(inv("X2", 300_000, null, true, TODAY.minusDays(1), TODAY.plusDays(30)), limit(100_000, 200_000d, 100_000, 0), WAC,
                Map.of("TF-09", Map.of("by", "cm1", "note", "buyer exposure reviewed, temporary peak")), TODAY);
        assertThat(ruled.status()).isEqualTo("PASS");
    }

    @Test
    void staleTenorOverdueAndWrongBuyerGstin() {
        var r = TradeRules.evaluate(inv("X3", 118_000, null, true, TODAY.minusDays(75), TODAY.plusDays(40)), limit(0, 700_000d, 0, 12), WAC, null, TODAY);
        assertThat(check(r, "TF-06").result()).isEqualTo("REFER");
        assertThat(check(r, "TF-07").result()).isEqualTo("REFER");
        assertThat(check(r, "TF-11").result()).isEqualTo("REFER");
        var g = new TradeRules.InvoiceFacts("X4", TODAY, TODAY.plusDays(30), SELLER, "27AADCD6610Q1Z4", 100_000d, 18_000d, 118_000d, null, true, Map.of());
        assertThat(check(TradeRules.evaluate(g, limit(0, 700_000d, 0, 0), WAC, null, TODAY), "TF-04").result()).isEqualTo("FAIL");
    }

    @Test
    void dealerProgrammeAnchorSellsToTheBorrower() {
        var l = new TradeRules.LimitFacts("DEALER", "ACTIVE", TODAY.plusMonths(11), 800_000, 0, 1L, "27GAWPG3318L1Z6", null, 0, 0, null);
        var f = new TradeRules.InvoiceFacts("SPL/26/7781", TODAY.minusDays(2), TODAY.plusDays(43), "27AABCS8890M1Z2", "27GAWPG3318L1Z6", 400_000d, 72_000d, 472_000d, null, true, Map.of());
        var r = TradeRules.evaluate(f, l, ANCHOR, null, TODAY);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.advance()).isEqualTo(472_000);
        var other = new TradeRules.Party(9L, "Other Paints", "ANCHOR", "DEALER", "27AAAAA1111A1Z1", "ACTIVE", true, 1.0, 60, 7, true, 1.0, null, 0);
        assertThat(check(TradeRules.evaluate(f, l, other, null, TODAY), "TF-02").result()).isEqualTo("FAIL");
    }

    @Test
    void structureChecks() {
        var ok = TradeRules.structure("RECEIVABLES", 1_000_000, "27DESPD4512K1Z3", "DESPD4512K", null,
                List.of(new TradeRules.SubFacts(WAC, 700_000), new TradeRules.SubFacts(new TradeRules.Party(4L, "Deccan Retail Mart Pvt Ltd", "BUYER", null, "27AADCD6610Q1Z4", "ACTIVE", true, 0.75, 60, 7, false, 0.4, 20_000_000d, 0), 300_000)));
        assertThat(TradeRules.structureOk(ok)).isTrue();
        var badGstin = TradeRules.structure("RECEIVABLES", 1_000_000, "27ABCDE1234F1Z5", "DESPD4512K", null, List.of(new TradeRules.SubFacts(WAC, 700_000)));
        assertThat(TradeRules.structureOk(badGstin)).isFalse();
        var noAnchor = TradeRules.structure("DEALER", 800_000, "27GAWPG3318L1Z6", "GAWPG3318L", null, List.of());
        assertThat(TradeRules.structureOk(noAnchor)).isFalse();
        var wrongProgramme = TradeRules.structure("VENDOR", 800_000, "27GAWPG3318L1Z6", "GAWPG3318L", ANCHOR, List.of());
        assertThat(wrongProgramme).anyMatch(c -> c.id().equals("LS-01") && "FAIL".equals(c.result()));
    }
}
