package com.rhythm.los.trade;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TradeSublimitRepository extends JpaRepository<TradeSublimit, Long> {
    List<TradeSublimit> findByLimitId(Long limitId);
    List<TradeSublimit> findByCounterpartyId(Long counterpartyId);
}
