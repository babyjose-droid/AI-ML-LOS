package com.rhythm.los.plan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LoanPlanRepository extends JpaRepository<LoanPlan, Long> {
    Optional<LoanPlan> findByApplicationId(Long applicationId);
    List<LoanPlan> findAllByOrderByIdDesc();
}
