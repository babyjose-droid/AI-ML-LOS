package com.rhythm.los.product;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductVersionRepository extends JpaRepository<ProductVersion, Long> {
    Optional<ProductVersion> findByProductCodeAndVersion(String productCode, int version);
    List<ProductVersion> findByProductCodeOrderByVersionDesc(String productCode);
    List<ProductVersion> findByStatus(String status);
    boolean existsByProductCode(String productCode);
}
