package com.rhythm.los.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HistoricalLoanRepository extends JpaRepository<HistoricalLoan, Long> {
    long countBySource(String source);
    List<HistoricalLoan> findAllByOrderByIdAsc();
    java.util.Optional<HistoricalLoan> findByRef(String ref);
    List<HistoricalLoan> findTop50BySourceOrderByIdDesc(String source);
    @org.springframework.data.jpa.repository.Query("select h.source, count(h), sum(case when h.outcomeBad = true then 1 else 0 end), sum(case when h.outcomeFraud = true then 1 else 0 end) from HistoricalLoan h group by h.source")
    List<Object[]> summary();
}
