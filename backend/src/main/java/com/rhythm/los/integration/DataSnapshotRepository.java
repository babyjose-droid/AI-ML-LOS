package com.rhythm.los.integration;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DataSnapshotRepository extends JpaRepository<DataSnapshot, Long> {
    Optional<DataSnapshot> findFirstByApplicationIdAndKindOrderByIdDesc(Long applicationId, String kind);
    List<DataSnapshot> findByApplicationIdOrderByIdDesc(Long applicationId);
}
