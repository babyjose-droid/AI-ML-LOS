package com.rhythm.los.trade;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TradeLimitRepository extends JpaRepository<TradeLimit, Long> {
    Optional<TradeLimit> findByApplicationId(Long applicationId);
    List<TradeLimit> findAllByOrderByIdDesc();
    List<TradeLimit> findByAnchorId(Long anchorId);
}
