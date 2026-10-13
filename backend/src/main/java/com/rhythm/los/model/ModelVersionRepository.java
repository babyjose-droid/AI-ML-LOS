package com.rhythm.los.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelVersionRepository extends JpaRepository<ModelVersion, Long> {
    Optional<ModelVersion> findFirstByStatusOrderByIdDesc(String status);
    Optional<ModelVersion> findFirstByKindAndStatusOrderByIdDesc(String kind, String status);
    List<ModelVersion> findAllByOrderByIdDesc();
    long countByName(String name);
}
