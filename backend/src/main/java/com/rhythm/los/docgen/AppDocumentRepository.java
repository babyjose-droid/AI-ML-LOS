package com.rhythm.los.docgen;

import java.util.List;

public interface AppDocumentRepository extends org.springframework.data.jpa.repository.JpaRepository<AppDocument, Long> {
    List<AppDocument> findByApplicationIdOrderByIdAsc(Long applicationId);
    List<AppDocument> findByApplicationIdAndStatusOrderByIdAsc(Long applicationId, String status);
    List<AppDocument> findByApplicationIdAndTemplateCodeOrderByIdDesc(Long applicationId, String templateCode);
}
