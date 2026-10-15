package com.rhythm.los.docgen;

import java.util.List;

public interface AppDocumentSignatureRepository extends org.springframework.data.jpa.repository.JpaRepository<AppDocumentSignature, Long> {
    List<AppDocumentSignature> findByAppDocumentIdOrderByIdAsc(Long appDocumentId);
}
