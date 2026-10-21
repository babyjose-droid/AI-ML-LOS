package com.rhythm.los.trade;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TradeCounterpartyRepository extends JpaRepository<TradeCounterparty, Long> {
    List<TradeCounterparty> findAllByOrderByNameAsc();
    Optional<TradeCounterparty> findByCode(String code);
}
