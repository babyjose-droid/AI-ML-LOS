package com.rhythm.los.scale;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CoLendingPartnerRepository extends JpaRepository<CoLendingPartner, Long> {
    List<CoLendingPartner> findByStatusOrderByIdAsc(String status);
}
