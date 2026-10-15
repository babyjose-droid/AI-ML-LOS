package com.rhythm.los.vkyc;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Call slots a customer (or staff on their behalf) can book: 15 minutes each within the product's call hours, for the next
 * few days, each taking as many calls as there are officials on duty. Pure, so it can be tested without the database.
 */
public final class VkycSlots {
    private VkycSlots() {}

    public static final int MINUTES = 15;
    public static final int DAYS = 3;
    /** When the product takes calls around the clock, slots are still offered in office hours. */
    static final LocalTime DEFAULT_FROM = LocalTime.of(9, 0), DEFAULT_TO = LocalTime.of(19, 0);

    public record Slot(Instant at, String label, int free) {}

    public static List<Slot> open(VkycPolicy p, ZonedDateTime now, int capacity, Map<Instant, Long> booked) {
        ZoneId ist = VkycPolicy.IST;
        ZonedDateTime n = now.withZoneSameInstant(ist);
        LocalTime from = p.allTheTime() ? DEFAULT_FROM : VkycPolicy.time(p.callFrom()), to = p.allTheTime() ? DEFAULT_TO : VkycPolicy.time(p.callTo());
        List<Slot> out = new ArrayList<>();
        for (int d = 0; d < DAYS; d++) {
            LocalDate day = n.toLocalDate().plusDays(d);
            for (LocalTime t = from; !t.plusMinutes(MINUTES).isAfter(to) && !t.isBefore(from); t = t.plusMinutes(MINUTES)) {
                ZonedDateTime at = ZonedDateTime.of(day, t, ist);
                if (!at.isAfter(n.plusMinutes(10))) continue;          // at least ten minutes ahead
                int free = (int) Math.max(0, capacity - booked.getOrDefault(at.toInstant(), 0L));
                out.add(new Slot(at.toInstant(), label(at, n), free));
                if (t.plusMinutes(MINUTES).isBefore(t)) break;         // wrapped past midnight
            }
        }
        return out;
    }

    public static boolean valid(VkycPolicy p, Instant at, ZonedDateTime now) {
        ZonedDateTime z = at.atZone(VkycPolicy.IST);
        if (z.getMinute() % MINUTES != 0 || z.getSecond() != 0 || z.getNano() != 0) return false;
        return open(p, now, 1, Map.of()).stream().anyMatch(s -> s.at().equals(at));
    }

    static String label(ZonedDateTime at, ZonedDateTime now) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate());
        String day = days == 0 ? "Today" : days == 1 ? "Tomorrow" : at.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) + " " + at.getDayOfMonth();
        return day + " " + at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH));
    }
}
