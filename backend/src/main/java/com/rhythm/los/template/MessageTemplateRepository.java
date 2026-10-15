package com.rhythm.los.template;

import java.util.List;
import java.util.Optional;

public interface MessageTemplateRepository extends org.springframework.data.jpa.repository.JpaRepository<MessageTemplate, Long> {
    Optional<MessageTemplate> findFirstByChannelAndCodeAndLanguageAndActiveTrueOrderByVersionDesc(String channel, String code, String language);
    List<MessageTemplate> findByChannelAndCodeAndLanguageOrderByVersionDesc(String channel, String code, String language);
    List<MessageTemplate> findByActiveTrueOrderByChannelAscCodeAscLanguageAscVersionDesc();
    List<MessageTemplate> findByChannelAndLanguageAndActiveTrue(String channel, String language);
}
