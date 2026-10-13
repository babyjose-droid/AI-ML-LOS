package com.rhythm.los.masters;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.HashSet;
import java.util.Set;

/**
 * Working-day clock for SLAs (holiday calendar master): a stage's SLA does not run on a holiday that applies to the
 * application's branch (or to every branch). Each such day in the SLA window pushes the due time by a day.
 */
@Service
public class BusinessCalendar {
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private final HolidayRepository holidays;

    public BusinessCalendar(HolidayRepository holidays) { this.holidays = holidays; }

    public Instant due(Instant start, long hours, Long branchId) {
        Instant due = start.plus(Duration.ofHours(hours));
        LocalDate from = start.atZone(IST).toLocalDate();
        Set<LocalDate> off = new HashSet<>();
        for (Holiday h : holidays.findByDateBetween(from, from.plusDays(hours / 24 + 60)))
            if (h.getBranchId() == null || h.getBranchId().equals(branchId)) off.add(h.getDate());
        if (off.isEmpty()) return due;
        LocalDate d = from;
        if (off.contains(d)) due = due.plus(Duration.ofDays(1));
        while (d.isBefore(due.atZone(IST).toLocalDate())) {
            d = d.plusDays(1);
            if (off.contains(d)) due = due.plus(Duration.ofDays(1));
        }
        return due;
    }
}
