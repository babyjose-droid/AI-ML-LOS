package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralValuationRepository extends JpaRepository<CollateralValuation, Long> {
    List<CollateralValuation> findByCollateralIdOrderByIdAsc(Long collateralId);
}
