package com.rhythm.los.party;

public interface PartyRequirementRepository extends org.springframework.data.jpa.repository.JpaRepository<PartyRequirement, Long> {
    java.util.List<PartyRequirement> findByApplicationIdOrderByIdAsc(Long applicationId);
}
