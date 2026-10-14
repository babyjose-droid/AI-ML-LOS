package com.rhythm.los.scale;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CapitalScenarioRepository extends JpaRepository<CapitalScenario, Long> {
    List<CapitalScenario> findTop20ByOrderByIdDesc();
}
