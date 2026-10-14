package com.rhythm.los.book;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoanSnapshotRepository extends JpaRepository<LoanSnapshot, Long> {
    List<LoanSnapshot> findByLoanIdOrderByFeedDateAsc(Long loanId);
    Optional<LoanSnapshot> findByLoanIdAndFeedDate(Long loanId, LocalDate feedDate);
    Optional<LoanSnapshot> findFirstByLoanIdAndFeedDateBeforeOrderByFeedDateDesc(Long loanId, LocalDate before);
    List<LoanSnapshot> findByFeedDate(LocalDate feedDate);
}
