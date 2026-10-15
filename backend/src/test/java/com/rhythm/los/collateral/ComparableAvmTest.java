package com.rhythm.los.collateral;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComparableAvmTest {
    static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    static ComparableAvm.Sale sale(long id, double lat, double lng, double area, double rate, int monthsAgo) {
        return new ComparableAvm.Sale(id, "RESIDENTIAL", "422013", "Gangapur Road", lat, lng, area, area * rate, TODAY.minusMonths(monthsAgo), "TEST");
    }

    @Test
    void valuesFromNearbyRecentSales() {
        List<ComparableAvm.Sale> s = List.of(sale(1, 20.007, 73.763, 1800, 5400, 2), sale(2, 20.005, 73.761, 1700, 5500, 4), sale(3, 20.008, 73.765, 1900, 5300, 6),
                sale(4, 20.004, 73.760, 2000, 5450, 3),
                // far away and too old: ignored
                sale(5, 20.200, 73.900, 1800, 9000, 2), sale(6, 20.006, 73.762, 1800, 2000, 40));
        ComparableAvm.Result r = ComparableAvm.value(new ComparableAvm.Subject("RESIDENTIAL", "422013", 20.0063, 73.7627, 1850, "GOOD"), s, TODAY, 0.06);
        assertThat(r).isNotNull();
        assertThat(r.comparables()).isEqualTo(4);
        assertThat(r.ratePerSqft()).isBetween(5300.0, 5700.0);
        assertThat(r.value()).isBetween(1850 * 5300.0, 1850 * 5700.0);
        assertThat(r.low()).isLessThanOrEqualTo(r.value());
        assertThat(r.confidence()).isBetween(0.4, 0.9);
        assertThat(r.used()).noneMatch(u -> u.id() == 5 || u.id() == 6);
        // poor condition takes 15% off
        ComparableAvm.Result poor = ComparableAvm.value(new ComparableAvm.Subject("RESIDENTIAL", "422013", 20.0063, 73.7627, 1850, "POOR"), s, TODAY, 0.06);
        assertThat(poor.value()).isLessThan(r.value());
    }

    @Test
    void needsThreeSalesAndFallsBackToPinCodeWithoutGps() {
        assertThat(ComparableAvm.value(new ComparableAvm.Subject("RESIDENTIAL", "422013", 20.0063, 73.7627, 1850, null),
                List.of(sale(1, 20.007, 73.763, 1800, 5400, 2), sale(2, 20.005, 73.761, 1700, 5500, 4)), TODAY, 0.06)).isNull();
        ComparableAvm.Result byPin = ComparableAvm.value(new ComparableAvm.Subject("RESIDENTIAL", "422013", null, null, 1000, null),
                List.of(sale(1, 20.007, 73.763, 1800, 5400, 2), sale(2, 20.005, 73.761, 1700, 5500, 4), sale(3, 20.008, 73.765, 1900, 5300, 6)), TODAY, 0.06);
        assertThat(byPin).isNotNull();
        assertThat(byPin.basis()).contains("PIN code");
        // a shop is not compared with houses
        assertThat(ComparableAvm.value(new ComparableAvm.Subject("SHOP", "422013", null, null, 400, null),
                List.of(sale(1, 20.007, 73.763, 1800, 5400, 2), sale(2, 20.005, 73.761, 1700, 5500, 4), sale(3, 20.008, 73.765, 1900, 5300, 6)), TODAY, 0.06)).isNull();
    }

    @Test
    void olderSalesAreBroughtToToday() {
        List<ComparableAvm.Sale> old = List.of(sale(1, 20.007, 73.763, 1800, 5000, 12), sale(2, 20.005, 73.761, 1800, 5000, 12), sale(3, 20.008, 73.765, 1800, 5000, 12));
        ComparableAvm.Result r = ComparableAvm.value(new ComparableAvm.Subject("RESIDENTIAL", "422013", 20.0063, 73.7627, 1800, null), old, TODAY, 0.06);
        assertThat(r.ratePerSqft()).isEqualTo(5300.0);
    }
}
