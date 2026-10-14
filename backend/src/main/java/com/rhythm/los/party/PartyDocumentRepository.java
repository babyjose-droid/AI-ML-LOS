package com.rhythm.los.party;

public interface PartyDocumentRepository extends org.springframework.data.jpa.repository.JpaRepository<PartyDocument, Long> {
    java.util.List<PartyDocument> findByPartyIdOrderByIdAsc(Long partyId);
}
