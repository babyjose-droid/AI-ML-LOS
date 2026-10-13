package com.rhythm.los.flow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AppStageRepository extends JpaRepository<AppStage, Long> {
    List<AppStage> findByApplicationId(Long applicationId);
    Optional<AppStage> findByApplicationIdAndStageKey(Long applicationId, String stageKey);
    List<AppStage> findByStatusAndDueAtBefore(String status, Instant before);
    List<AppStage> findByStatusIn(List<String> statuses);
}
