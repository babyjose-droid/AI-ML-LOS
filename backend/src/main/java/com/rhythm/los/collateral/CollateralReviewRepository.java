package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralReviewRepository extends JpaRepository<CollateralReview, Long> {
    List<CollateralReview> findByCollateralIdOrderByIdAsc(Long collateralId);
    List<CollateralReview> findByStatusOrderByDueAtAsc(String status);
}
