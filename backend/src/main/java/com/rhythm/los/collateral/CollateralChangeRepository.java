package com.rhythm.los.collateral;

public interface CollateralChangeRepository extends org.springframework.data.jpa.repository.JpaRepository<CollateralChange, Long> {
    java.util.List<CollateralChange> findByLoanIdOrderByIdDesc(Long loanId);
    java.util.List<CollateralChange> findByStatusInOrderByIdAsc(java.util.Collection<String> statuses);
}
