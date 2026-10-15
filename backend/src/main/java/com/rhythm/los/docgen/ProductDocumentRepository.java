package com.rhythm.los.docgen;

import java.util.List;
import java.util.Optional;

public interface ProductDocumentRepository extends org.springframework.data.jpa.repository.JpaRepository<ProductDocument, Long> {
    List<ProductDocument> findByProductCodeOrderBySortAscIdAsc(String productCode);
    Optional<ProductDocument> findByProductCodeAndTemplateCode(String productCode, String templateCode);
    long countByTemplateCode(String templateCode);
}
