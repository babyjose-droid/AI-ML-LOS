package com.rhythm.los.sanction;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SanctionRecordRepository extends JpaRepository<SanctionRecord, Long> {
    List<SanctionRecord> findByApplicationIdOrderByIdAsc(Long applicationId);
    Optional<SanctionRecord> findFirstByApplicationIdAndActionOrderByIdDesc(Long applicationId, String action);
}
