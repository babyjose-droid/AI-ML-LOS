package com.rhythm.los.risk;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface HouseholdMemberRepository extends JpaRepository<HouseholdMember, Long> {
    List<HouseholdMember> findByApplicationIdOrderByIdAsc(Long applicationId);
    void deleteByApplicationId(Long applicationId);
}
