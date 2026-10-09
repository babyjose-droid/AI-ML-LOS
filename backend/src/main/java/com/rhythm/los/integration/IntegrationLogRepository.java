package com.rhythm.los.integration;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IntegrationLogRepository extends JpaRepository<IntegrationLog, Long> {
    List<IntegrationLog> findByApplicationIdOrderByIdDesc(Long applicationId);
    List<IntegrationLog> findByStatusOrderByIdDesc(String status);
    List<IntegrationLog> findAllByOrderByIdDesc(Pageable page);
    long countByStatus(String status);
    List<IntegrationLog> findByApplicationIdAndOperationAndStatus(Long applicationId, String operation, String status);
}
