package com.rhythm.los.plan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlanDocumentRepository extends JpaRepository<PlanDocument, Long> {
    List<PlanDocument> findByApplicationIdOrderByIdAsc(Long applicationId);
}
