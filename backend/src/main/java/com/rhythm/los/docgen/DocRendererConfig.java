package com.rhythm.los.docgen;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DocRendererConfig {
    @Bean
    DocRenderer docRenderer(@Value("${rhythm.document-ai.url:}") String url, @Value("${rhythm.document-ai.timeout-seconds:180}") int timeout) {
        return url == null || url.isBlank() ? new SimplePdf() : new HttpDocRenderer(url, timeout);
    }
}
