package com.rhythm.los.flow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowVersionRepository extends JpaRepository<WorkflowVersion, Long> {
    Optional<WorkflowVersion> findFirstByProductCodeAndStatusOrderByVersionDesc(String productCode, String status);
    Optional<WorkflowVersion> findByProductCodeAndVersion(String productCode, int version);
    List<WorkflowVersion> findByProductCodeOrderByVersionDesc(String productCode);
    List<WorkflowVersion> findAllByOrderByProductCodeAscVersionDesc();
}
