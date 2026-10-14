package com.rhythm.los.collateral;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CollateralPolicyRepository extends JpaRepository<CollateralPolicy, String> {

}
