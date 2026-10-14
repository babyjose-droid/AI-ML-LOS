package com.rhythm.los.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CollectionContactRepository extends JpaRepository<CollectionContact, Long> {
    List<CollectionContact> findByLoanIdOrderByIdDesc(Long loanId);
}
