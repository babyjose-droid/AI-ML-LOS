package com.rhythm.los.docgen;

import java.util.List;
import java.util.Optional;

public interface DocTemplateRepository extends org.springframework.data.jpa.repository.JpaRepository<DocTemplate, Long> {
    List<DocTemplate> findAllByOrderByCodeAscLanguageAscVersionDesc();
    List<DocTemplate> findByCodeAndLanguageOrderByVersionDesc(String code, String language);
    List<DocTemplate> findByCodeOrderByVersionDesc(String code);
    Optional<DocTemplate> findFirstByCodeAndLanguageAndStatusOrderByVersionDesc(String code, String language, String status);
    List<DocTemplate> findByStatus(String status);
    long countByCode(String code);
}
