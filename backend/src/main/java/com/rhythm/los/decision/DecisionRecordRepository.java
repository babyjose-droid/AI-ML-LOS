package com.rhythm.los.decision;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DecisionRecordRepository extends JpaRepository<DecisionRecord, Long> {
    Optional<DecisionRecord> findFirstByApplicationIdOrderByIdDesc(Long applicationId);
    List<DecisionRecord> findByApplicationIdOrderByIdDesc(Long applicationId);
}
