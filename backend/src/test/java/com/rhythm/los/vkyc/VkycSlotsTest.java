package com.rhythm.los.vkyc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VkycSlotsTest {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final VkycPolicy LAP = new VkycPolicy("ALL", 0.45, true, true, 48, "09:00", "19:00", 2, List.of("OPERATIONS"), List.of(), List.of("en"));

    @Test
    void offersQuarterHoursWithinCallHoursAhead() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 14, 18, 20, 0, 0, IST);
        List<VkycSlots.Slot> s = VkycSlots.open(LAP, now, 2, Map.of());
        // today: only 18:45 is ten or more minutes ahead and ends by 19:00
        assertThat(s.get(0).label()).isEqualTo("Today 6:45 PM");
        assertThat(s.get(1).label()).isEqualTo("Tomorrow 9:00 AM");
        assertThat(s).hasSize(1 + 2 * 40);
        assertThat(s.get(0).free()).isEqualTo(2);
    }

    @Test
    void fullSlotsShowNoRoomAndBookingsMustFitTheGrid() {
        ZonedDateTime now = ZonedDateTime.of(2026, 10, 14, 8, 0, 0, 0, IST);
        Instant nine = ZonedDateTime.of(2026, 10, 14, 9, 0, 0, 0, IST).toInstant();
        List<VkycSlots.Slot> s = VkycSlots.open(LAP, now, 2, Map.of(nine, 2L));
        assertThat(s.get(0).at()).isEqualTo(nine);
        assertThat(s.get(0).free()).isZero();
        assertThat(VkycSlots.valid(LAP, nine, now)).isTrue();
        assertThat(VkycSlots.valid(LAP, nine.plusSeconds(60 * 7), now)).isFalse();
        assertThat(VkycSlots.valid(LAP, ZonedDateTime.of(2026, 10, 14, 20, 0, 0, 0, IST).toInstant(), now)).isFalse();
        assertThat(VkycSlots.valid(LAP, ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, IST).toInstant(), now)).isFalse();
        // round the clock products still offer office-hour slots
        assertThat(VkycSlots.open(VkycPolicy.DEFAULT, now, 1, Map.of()).get(0).label()).isEqualTo("Today 9:00 AM");
    }
}
