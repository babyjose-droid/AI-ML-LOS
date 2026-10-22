package com.rhythm.los.pledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MarginCallRepository extends JpaRepository<MarginCall, Long> {
    List<MarginCall> findByPledgeIdOrderByIdDesc(Long pledgeId);
    List<MarginCall> findAllByOrderByIdDesc();
    List<MarginCall> findByStatus(String status);
}
