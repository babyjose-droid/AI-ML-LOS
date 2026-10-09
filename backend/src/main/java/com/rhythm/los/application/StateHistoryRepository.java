package com.rhythm.los.application;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface StateHistoryRepository extends JpaRepository<StateHistory, Long> {
    List<StateHistory> findByApplicationIdOrderByIdAsc(Long applicationId);
}
