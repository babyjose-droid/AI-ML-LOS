package com.rhythm.los.collateral;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class CollateralEngineTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    static CollateralEngine.Policy residential() {
        return new CollateralEngine.Policy("RESIDENTIAL", 13, 30, 0.10, 0.65, 6, 5_000_000d, 0.15, 5, 250, 0.70,
                List.of("SALE_DEED", "EC", "KHATA", "PROPERTY_TAX", "VALUATION_REPORT"), List.of("EXTERIOR_FRONT", "EXTERIOR_SIDE", "INTERIOR_HALL", "INTERIOR_KITCHEN", "STREET_VIEW"));
    }

    static Map<String, Object> tx(String date, String type, String no, String from, String to, String linked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("date", date); m.put("type", type); m.put("docNo", no); m.put("from", from); m.put("to", to); m.put("linkedRef", linked);
        return m;
    }

    static List<CollateralEngine.Doc> docs(String ecSurvey, List<Map<String, Object>> ecTx) {
        List<CollateralEngine.Doc> d = new ArrayList<>();
        d.add(new CollateralEngine.Doc(1, "SALE_DEED", Map.of("owner", "Vikas Deshpande", "seller", "Anil Kulkarni", "surveyNo", "CTS1234/5", "areaSqft", 1850, "registrationDate", "2016-05-12"), List.of(), 1, false));
        d.add(new CollateralEngine.Doc(2, "EC", Map.of("periodFrom", "2012-01-01", "periodTo", "2026-10-01", "surveyNo", ecSurvey), ecTx, 1, false));
        d.add(new CollateralEngine.Doc(3, "KHATA", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC-44521"), List.of(), 1, false));
        d.add(new CollateralEngine.Doc(4, "PROPERTY_TAX", Map.of("owner", "Vikas Deshpande", "municipalId", "NMC 44521"), List.of(), 1, false));
        d.add(new CollateralEngine.Doc(5, "VALUATION_REPORT", Map.of("marketValue", 8_500_000), List.of(), 1, false));
        return d;
    }

    static List<CollateralEngine.Media> photos(double dist, String reuse) {
        List<CollateralEngine.Media> m = new ArrayList<>();
        long i = 10;
        for (String l : List.of("EXTERIOR_FRONT", "EXTERIOR_SIDE", "INTERIOR_HALL", "INTERIOR_KITCHEN", "STREET_VIEW"))
            m.add(new CollateralEngine.Media(i++, l, dist, "GOOD", i == 11 ? reuse : null, 0, false));
        return m;
    }

    static List<CollateralEngine.Val> vals(double... values) {
        List<CollateralEngine.Val> v = new ArrayList<>();
        long id = 100;
        for (double x : values) v.add(new CollateralEngine.Val(id++, "EMPANELLED", TODAY.minusDays(20), x, x * 0.9, x * 0.75, true, "Valuer " + id));
        return v;
    }

    static CollateralEngine.Inputs in(double requested, List<CollateralEngine.Doc> docs, List<CollateralEngine.Media> media, List<CollateralEngine.Val> vals,
                                      List<CollateralEngine.Ruling> rulings) {
        return new CollateralEngine.Inputs(residential(), requested, new CollateralEngine.Facts("GOOD", "COMPLETE", "Vikas Deshpande", "Vikas Deshpande", "CTS 1234/5", "NMC-44521"),
                docs, media, vals, 8_000_000d, 0.6, rulings, TODAY);
    }

    static final List<Map<String, Object>> CLEAN = List.of(
            tx("2016-05-12", "SALE", "4521/2016", "Anil Kulkarni", "Vikas Deshpande", null),
            tx("2016-06-20", "MORTGAGE", "4890/2016", "Vikas Deshpande", "State Bank of India", null),
            tx("2021-03-15", "RELEASE", "2210/2021", "State Bank of India", "Vikas Deshpande", "4890/2016"));

    static CollateralEngine.Hit hit(CollateralEngine.Result r, String id) { return r.rules().stream().filter(h -> h.id().equals(id)).findFirst().orElseThrow(); }

    @Test
    void cleanResidentialPassesWithLtv() {
        CollateralEngine.Result r = CollateralEngine.evaluate(in(4_675_000, docs("CTS1234/5", CLEAN), photos(30, null), vals(8_500_000, 8_900_000), List.of()));
        assertThat(r.rules().stream().filter(h -> !h.passed()).map(CollateralEngine.Hit::id).toList()).isEmpty();
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.ltv().get("valueUsed")).isEqualTo(8_500_000L);
        assertThat(r.ltv().get("eligibleValue")).isEqualTo(7_650_000L);
        assertThat(r.ltv().get("maxExposure")).isEqualTo(4_972_500L);
        assertThat(r.ltv().get("ltvRequested")).isEqualTo(0.55);
        assertThat(r.ec().get("latestOwner")).isEqualTo("Vikas Deshpande");
        assertThat((List<?>) r.ec().get("openCharges")).isEmpty();
        assertThat(r.risk()).hasSize(10);
        assertThat(r.risk().stream().mapToDouble(CollateralEngine.Dim::weight).sum()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.riskScore()).isLessThan(25);
        assertThat(r.conditions()).anyMatch(c -> c.contains("Original title documents"));
        assertThat(r.reviews()).isEmpty();
    }

    @Test
    void openChargeIdMismatchAndValuationGapAreReferredThenClearedWithLtvCap() {
        List<Map<String, Object>> tx = List.of(tx("2014-08-02", "SALE", "3301/2014", "Kishor Jain", "Vikas Deshpande", null),
                tx("2019-02-11", "MORTGAGE", "1187/2019", "Vikas Deshpande", "Janata Sahakari Bank", null));
        CollateralEngine.Inputs i = in(5_000_000, docs("CTS1234/6", tx), photos(30, null), vals(4_950_000, 6_187_500), List.of());
        CollateralEngine.Result r = CollateralEngine.evaluate(i);
        assertThat(r.status()).isEqualTo("REFER");
        assertThat(hit(r, "CR-04").passed()).isFalse();
        assertThat(hit(r, "CR-04").reason()).contains("1187/2019");
        assertThat(hit(r, "CR-05").passed()).isFalse();
        assertThat(hit(r, "CR-08").reason()).contains("25.0%");
        assertThat(hit(r, "CR-11").passed()).isFalse();
        assertThat(r.reviews()).extracting(CollateralEngine.ReviewNeed::type).containsExactlyInAnyOrder("LEGAL", "LEGAL", "VALUATION");
        // reviewers clear each flag with a reason: PASS, with the LTV cap as a condition
        List<CollateralEngine.Ruling> cleared = r.reviews().stream().map(n -> new CollateralEngine.Ruling(n.ruleId(), n.reason(), "CLEARED")).toList();
        CollateralEngine.Result after = CollateralEngine.evaluate(new CollateralEngine.Inputs(i.pol(), i.requested(), i.facts(), i.docs(), i.media(), i.vals(), i.aiValue(), i.aiConfidence(), cleared, TODAY));
        assertThat(after.status()).isEqualTo("PASS");
        assertThat(after.ltv().get("maxExposure")).isEqualTo(2_895_750L);
        assertThat(after.conditions()).anyMatch(c -> c.startsWith("LTV CAP"));
        // an upheld flag fails the collateral
        CollateralEngine.Result upheld = CollateralEngine.evaluate(new CollateralEngine.Inputs(i.pol(), i.requested(), i.facts(), i.docs(), i.media(), i.vals(), i.aiValue(), i.aiConfidence(),
                List.of(new CollateralEngine.Ruling("CR-04", hit(r, "CR-04").reason(), "UPHELD")), TODAY));
        assertThat(upheld.status()).isEqualTo("FAIL");
    }

    @Test
    void saleToAThirdPartyReusedPhotoAndFarGpsFail() {
        List<Map<String, Object>> tx = List.of(tx("2016-05-12", "SALE", "4521/2016", "Anil Kulkarni", "Vikas Deshpande", null),
                tx("2024-07-09", "SALE", "5120/2024", "Vikas Deshpande", "Manoj Verma", null));
        CollateralEngine.Result r = CollateralEngine.evaluate(in(3_000_000, docs("CTS1234/5", tx), photos(2300, "COL-5001"), vals(8_500_000, 8_600_000), List.of()));
        assertThat(r.status()).isEqualTo("FAIL");
        assertThat(hit(r, "CR-01").reason()).contains("Manoj Verma");
        assertThat(hit(r, "CR-09").passed()).isFalse();
        assertThat(hit(r, "CR-10").reason()).contains("COL-5001");
        assertThat(r.risk().stream().filter(d -> d.name().equals("Fraud")).findFirst().orElseThrow().score()).isEqualTo(100);
        assertThat(r.riskScore()).isGreaterThanOrEqualTo(40);
    }

    @Test
    void titleChainFlagsDoubleSaleAndGaps() {
        List<Map<String, Object>> tx = List.of(tx("2010-01-05", "SALE", "100/2010", "A Rao", "B Rao", null),
                tx("2012-01-05", "SALE", "200/2012", "C Iyer", "Vikas Deshpande", null),
                tx("2013-01-05", "SALE", "300/2013", "C Iyer", "D Shah", null));
        List<CollateralEngine.Doc> d = List.of(new CollateralEngine.Doc(2, "EC", Map.of("periodFrom", "2000-01-01", "periodTo", "2026-10-01"), tx, 1, false));
        CollateralEngine.Inputs i = in(1, d, List.of(), List.of(), List.of());
        List<Map<String, Object>> t = CollateralEngine.title(i, CollateralEngine.ec(i));
        assertThat(t).hasSize(3);
        assertThat(t.get(1).get("flag")).isEqualTo("GAP");
        assertThat(t.get(2).get("flag")).isEqualTo("CONFLICT");
    }

    @Test
    void missingDocumentsUnconfirmedFieldsAndOldValuation() {
        List<CollateralEngine.Doc> d = new ArrayList<>(docs("CTS1234/5", CLEAN));
        d.removeIf(x -> x.type().equals("KHATA"));
        d.set(0, new CollateralEngine.Doc(1, "SALE_DEED", d.get(0).fields(), List.of(), 0.6, false));
        List<CollateralEngine.Val> v = List.of(new CollateralEngine.Val(1, "EMPANELLED", TODAY.minusMonths(9), 4_000_000, null, null, true, "X"));
        CollateralEngine.Result r = CollateralEngine.evaluate(in(2_000_000, d, photos(30, null), v, List.of()));
        assertThat(hit(r, "CR-03").reason()).contains("KHATA").contains("Fields to confirm: DOC-1 SALE_DEED");
        assertThat(hit(r, "CR-07").reason()).contains("9 months");
        assertThat(r.status()).isEqualTo("REFER");
    }

    @Test
    void goldUsesRbiLtvTiersAndUnifiedDecision() {
        assertThat(CollateralEngine.goldMaxLtv(200_000)).isEqualTo(0.85);
        assertThat(CollateralEngine.goldMaxLtv(400_000)).isEqualTo(0.80);
        assertThat(CollateralEngine.goldMaxLtv(900_000)).isEqualTo(0.75);
        CollateralEngine.Policy gold = new CollateralEngine.Policy("GOLD", 0, 0, 0, 0.75, 1, null, 0.15, 0, 0, 0, List.of("APPRAISAL"), List.of());
        CollateralEngine.Inputs i = new CollateralEngine.Inputs(gold, 200_000, new CollateralEngine.Facts(null, null, null, "Asha", null, null), List.of(), List.of(),
                List.of(new CollateralEngine.Val(1, "APPRAISAL", TODAY, 250_000, null, null, true, "co1")), null, 0, List.of(), TODAY);
        CollateralEngine.Result r = CollateralEngine.evaluate(i);
        assertThat(r.status()).isEqualTo("PASS");
        assertThat(r.ltv().get("maxExposure")).isEqualTo(212_500L);
        assertThat(CollateralEngine.unified("APPROVE", "PASS", List.of("x"), 212_500d, 200_000).get("decision")).isEqualTo("APPROVE_WITH_CONDITIONS");
        assertThat(CollateralEngine.unified("APPROVE", "REFER", List.of(), null, 1).get("decision")).isEqualTo("REFER");
        assertThat(CollateralEngine.unified("REFER", "PASS", List.of(), null, 1).get("decision")).isEqualTo("REFER");
        assertThat(CollateralEngine.unified("APPROVE", "FAIL", List.of(), null, 1).get("decision")).isEqualTo("REJECT");
        assertThat(CollateralEngine.unified("REJECT", "PASS", List.of(), null, 1).get("decision")).isEqualTo("REJECT");
    }
}
