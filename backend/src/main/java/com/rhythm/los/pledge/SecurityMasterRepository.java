package com.rhythm.los.pledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SecurityMasterRepository extends JpaRepository<SecurityMaster, String> {
    List<SecurityMaster> findAllByOrderByNameAsc();
}
