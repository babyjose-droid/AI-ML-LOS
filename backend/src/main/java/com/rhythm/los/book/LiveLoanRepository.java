package com.rhythm.los.book;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface LiveLoanRepository extends JpaRepository<LiveLoan, Long> {
    Optional<LiveLoan> findByLoanRef(String loanRef);
    Optional<LiveLoan> findByApplicationId(Long applicationId);
    List<LiveLoan> findByStatusOrderByIdAsc(String status);
    long countBySource(String source);
}
