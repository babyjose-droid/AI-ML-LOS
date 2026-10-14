package com.rhythm.los.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LoanRestructureRepository extends JpaRepository<LoanRestructure, Long> {
    List<LoanRestructure> findByLoanIdOrderByIdDesc(Long loanId);
    List<LoanRestructure> findByStatusOrderByIdAsc(String status);
}
