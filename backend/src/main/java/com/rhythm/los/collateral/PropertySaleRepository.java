package com.rhythm.los.collateral;

public interface PropertySaleRepository extends org.springframework.data.jpa.repository.JpaRepository<PropertySale, Long> {
    java.util.List<PropertySale> findByTypeAndPincodeStartingWith(String type, String pinPrefix);
    java.util.List<PropertySale> findTop200ByOrderByIdDesc();
}
