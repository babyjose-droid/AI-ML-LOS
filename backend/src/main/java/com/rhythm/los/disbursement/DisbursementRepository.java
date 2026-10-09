package com.rhythm.los.disbursement;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long> {
    List<Disbursement> findByApplicationIdOrderByIdAsc(Long applicationId);
}
