package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralMediaRepository extends JpaRepository<CollateralMedia, Long> {
    List<CollateralMedia> findByCollateralIdOrderByIdAsc(Long collateralId);
    List<CollateralMedia> findBySha256(String sha256);
    List<CollateralMedia> findByDhashIsNotNull();
}
