package com.rhythm.los.field;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FieldPhotoRepository extends JpaRepository<FieldPhoto, Long> {
    List<FieldPhoto> findByVisitIdOrderByIdAsc(Long visitId);
    List<FieldPhoto> findBySha256(String sha256);
    List<FieldPhoto> findByApplicationIdNot(Long applicationId);
}
