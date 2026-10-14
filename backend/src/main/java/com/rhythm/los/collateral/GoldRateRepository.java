package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;

public interface GoldRateRepository extends JpaRepository<GoldRate, Long> {
    List<GoldRate> findByRateDateAfterOrderByRateDateDesc(LocalDate after);
    Optional<GoldRate> findFirstByOrderByRateDateDesc();
    Optional<GoldRate> findByRateDate(LocalDate d);
}
