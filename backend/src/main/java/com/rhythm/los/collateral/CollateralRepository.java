package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralRepository extends JpaRepository<Collateral, Long> {
    List<Collateral> findByApplicationIdOrderByIdAsc(Long applicationId);
    List<Collateral> findAllByOrderByIdDesc();
}
