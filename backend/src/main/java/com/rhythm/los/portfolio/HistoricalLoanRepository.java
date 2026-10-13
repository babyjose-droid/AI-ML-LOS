package com.rhythm.los.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricalLoanRepository extends JpaRepository<HistoricalLoan, Long> {
    long countBySource(String source);
    List<HistoricalLoan> findAllByOrderByIdAsc();
}
