package com.rhythm.los.pledge;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PledgeDocumentRepository extends JpaRepository<PledgeDocument, Long> {
    List<PledgeDocument> findByPledgeIdOrderByIdAsc(Long pledgeId);
}
