package com.rhythm.los.asset;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface DealerRepository extends JpaRepository<Dealer, Long> {
    List<Dealer> findAllByOrderByNameAsc();
    Optional<Dealer> findByCode(String code);
}
