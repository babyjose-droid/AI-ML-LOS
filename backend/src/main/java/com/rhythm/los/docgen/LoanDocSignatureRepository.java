package com.rhythm.los.docgen;

import java.util.List;

public interface LoanDocSignatureRepository extends org.springframework.data.jpa.repository.JpaRepository<LoanDocSignature, Long> {
    List<LoanDocSignature> findByAppDocumentIdOrderByIdAsc(Long appDocumentId);
}
