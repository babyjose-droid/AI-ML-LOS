package com.rhythm.los.plan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlanPartyRepository extends JpaRepository<PlanParty, Long> {
    List<PlanParty> findAllByOrderByNameAsc();
    Optional<PlanParty> findByCode(String code);
}
