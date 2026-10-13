package com.rhythm.los.masters;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface HolidayRepository extends JpaRepository<Holiday, Long> {
    List<Holiday> findAllByOrderByDateAsc();
    List<Holiday> findByDateBetween(LocalDate from, LocalDate to);
    boolean existsByDateAndBranchId(LocalDate date, Long branchId);
    boolean existsByDateAndBranchIdIsNull(LocalDate date);
}
