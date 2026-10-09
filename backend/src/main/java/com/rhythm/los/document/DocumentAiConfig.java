package com.rhythm.los.document;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocumentAiConfig {
    @Bean
    DocumentReader documentReader(@Value("${rhythm.document-ai.url:}") String url,
                                  @Value("${rhythm.document-ai.timeout-seconds:90}") int timeout) {
        return url == null || url.isBlank() ? new FilenameDocumentReader() : new HttpDocumentReader(url, timeout);
    }
}
