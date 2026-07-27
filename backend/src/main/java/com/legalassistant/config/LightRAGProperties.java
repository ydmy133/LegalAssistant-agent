package com.legalassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legal.lightrag")
public class LightRAGProperties {

    private String baseUrl = "http://localhost:9621";
    private String apiKey = "";
    private String queryMode = "hybrid";
    private long ingestPollIntervalMs = 2000;
    private long ingestPollTimeoutSeconds = 600;
    private String fileSourcePrefix = "legal-doc";
}
