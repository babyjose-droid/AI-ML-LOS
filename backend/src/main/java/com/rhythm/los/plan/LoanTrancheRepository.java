package com.rhythm.los.plan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LoanTrancheRepository extends JpaRepository<LoanTranche, Long> {
    List<LoanTranche> findByApplicationIdOrderBySeqAsc(Long applicationId);
    List<LoanTranche> findByPayeeId(Long payeeId);
}
