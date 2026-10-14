package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralDocumentRepository extends JpaRepository<CollateralDocument, Long> {
    List<CollateralDocument> findByCollateralIdOrderByIdAsc(Long collateralId);
}
