package com.rhythm.los.memo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditMemoRepository extends JpaRepository<CreditMemo, Long> {
    List<CreditMemo> findByApplicationIdOrderByVersionDesc(Long applicationId);
    Optional<CreditMemo> findFirstByApplicationIdOrderByVersionDesc(Long applicationId);
}
