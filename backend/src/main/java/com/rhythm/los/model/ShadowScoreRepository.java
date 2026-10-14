package com.rhythm.los.model;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ShadowScoreRepository extends JpaRepository<ShadowScore, Long> {
    List<ShadowScore> findByModelVersionIdOrderByIdDesc(Long modelVersionId);
}
