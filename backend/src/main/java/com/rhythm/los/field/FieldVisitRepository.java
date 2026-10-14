package com.rhythm.los.field;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface FieldVisitRepository extends JpaRepository<FieldVisit, Long> {
    Optional<FieldVisit> findByClientId(String clientId);
    List<FieldVisit> findByApplicationIdOrderByIdDesc(Long applicationId);
}
