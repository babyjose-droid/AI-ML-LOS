package com.rhythm.los.vkyc;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VkycSessionRepository extends JpaRepository<VkycSession, Long> {
    Optional<VkycSession> findByTokenHash(String tokenHash);
    List<VkycSession> findByApplicationIdOrderByIdDesc(Long applicationId);
    List<VkycSession> findByStatusInOrderByIdAsc(List<String> statuses);
}
