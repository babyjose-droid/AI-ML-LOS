package com.rhythm.los.growth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TopupOfferRepository extends JpaRepository<TopupOffer, Long> {
    List<TopupOffer> findByLoanIdOrderByIdDesc(Long loanId);
    List<TopupOffer> findByStatusOrderByIdDesc(String status);
}
