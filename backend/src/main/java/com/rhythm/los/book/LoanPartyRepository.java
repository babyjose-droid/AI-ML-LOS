package com.rhythm.los.book;

public interface LoanPartyRepository extends org.springframework.data.jpa.repository.JpaRepository<LoanParty, Long> {
    java.util.List<LoanParty> findByLoanIdOrderByIdAsc(Long loanId);
    java.util.List<LoanParty> findByLoanIdIn(java.util.Collection<Long> loanIds);
}
