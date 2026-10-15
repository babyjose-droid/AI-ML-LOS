package com.rhythm.los.docgen;

import java.util.List;

public interface LoanDocRepository extends org.springframework.data.jpa.repository.JpaRepository<LoanDoc, Long> {
    List<LoanDoc> findByApplicationIdOrderByIdAsc(Long applicationId);
    List<LoanDoc> findByApplicationIdAndStatusOrderByIdAsc(Long applicationId, String status);
    List<LoanDoc> findByApplicationIdAndTemplateCodeOrderByIdDesc(Long applicationId, String templateCode);
}
