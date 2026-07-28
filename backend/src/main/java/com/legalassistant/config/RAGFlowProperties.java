package com.legalassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "legal.ragflow")
public class RAGFlowProperties {

    private String baseUrl = "http://localhost:9380";
    private String apiKey = "";
    private String datasetId = "";
    private int pageSize = 5;
    private double similarityThreshold = 0.2;
    private long ingestPollIntervalMs = 2000;
    private long ingestPollTimeoutSeconds = 600;
    private String fileNamePrefix = "legal-doc";
}
