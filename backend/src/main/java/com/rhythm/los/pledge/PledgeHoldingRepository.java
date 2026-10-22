package com.rhythm.los.pledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PledgeHoldingRepository extends JpaRepository<PledgeHolding, Long> {
    List<PledgeHolding> findByPledgeIdOrderByIdAsc(Long pledgeId);
}
