package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralEvaluationRepository extends JpaRepository<CollateralEvaluation, Long> {
    Optional<CollateralEvaluation> findFirstByCollateralIdOrderByIdDesc(Long collateralId);
    List<CollateralEvaluation> findByCollateralIdOrderByIdDesc(Long collateralId);
}
