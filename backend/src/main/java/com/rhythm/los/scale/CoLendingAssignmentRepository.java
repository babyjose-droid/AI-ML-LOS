package com.rhythm.los.scale;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CoLendingAssignmentRepository extends JpaRepository<CoLendingAssignment, Long> {
    List<CoLendingAssignment> findByBatchOrderByIdAsc(String batch);
    List<CoLendingAssignment> findAllByOrderByIdDesc();
    List<CoLendingAssignment> findByApplicationId(Long applicationId);
}
