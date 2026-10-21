package com.rhythm.los.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long> {
    Optional<Asset> findByApplicationId(Long applicationId);
    List<Asset> findByDealerId(Long dealerId);
    List<Asset> findAllByOrderByIdDesc();
}
