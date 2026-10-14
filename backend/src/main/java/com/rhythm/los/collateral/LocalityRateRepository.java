package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LocalityRateRepository extends JpaRepository<LocalityRate, Long> {
    List<LocalityRate> findByTypeOrderByIdAsc(String type);
}
