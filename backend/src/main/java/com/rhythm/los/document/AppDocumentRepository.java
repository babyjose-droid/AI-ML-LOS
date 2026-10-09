package com.rhythm.los.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AppDocumentRepository extends JpaRepository<AppDocument, Long> {
    List<AppDocument> findByApplicationIdOrderByIdAsc(Long applicationId);
}
