package com.rhythm.los.document;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KycCheckRepository extends JpaRepository<KycCheck, Long> {
    List<KycCheck> findByApplicationIdOrderByIdAsc(Long applicationId);
    void deleteByApplicationId(Long applicationId);
}
