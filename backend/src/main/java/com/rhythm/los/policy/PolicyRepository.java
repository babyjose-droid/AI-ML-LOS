package com.rhythm.los.policy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository extends JpaRepository<PolicyVersion, Long> {
    Optional<PolicyVersion> findFirstByStatusOrderByIdDesc(String status);
    List<PolicyVersion> findAllByOrderByIdDesc();
}
