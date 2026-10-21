package com.rhythm.los.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AssetDocumentRepository extends JpaRepository<AssetDocument, Long> {
    List<AssetDocument> findByAssetIdOrderByIdAsc(Long assetId);
}
