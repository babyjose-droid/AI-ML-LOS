package com.rhythm.los.audit;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByApplicationIdOrderByIdDesc(Long applicationId);
    List<AuditEvent> findByActionIn(java.util.Collection<String> actions);
    List<AuditEvent> findAllByOrderByIdDesc(Pageable page);
    List<AuditEvent> findByCreatedAtBetweenOrderByIdAsc(java.time.Instant from, java.time.Instant to);
}
