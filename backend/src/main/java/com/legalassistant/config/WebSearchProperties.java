package com.legalassistant.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Data
@Component
@ConfigurationProperties(prefix = "legal.web-search")
public class WebSearchProperties {

    private boolean enabled = true;
    /** 默认 searxng（无商业 API Key） */
    private String provider = "searxng";
    private String searxngBaseUrl = "http://localhost:8088";
    private int maxResults = 5;
    private int timeoutSeconds = 15;
    private int fetchTimeoutSeconds = 10;
    private int maxFetchPages = 3;
    private int maxContentChars = 3000;
    private String userAgent = "LegalAssistantBot/1.0 (+https://legal-assistant.local)";
    private boolean preferIncludeDomains = true;
    private String includeDomains = "gov.cn,court.gov.cn,moj.gov.cn,12348.gov.cn";
    private boolean autoFallback = true;
    private boolean curatedFallback = true;
    private int maxFusedSegments = 16;
    private int minLocalChars = 200;
    private SourceWeights sourceWeights = new SourceWeights();

    @Data
    public static class SourceWeights {
        private double local = 1.0;
        private double gov = 0.90;
        private double web = 0.65;
    }

    public List<String> includeDomainList() {
        if (includeDomains == null || includeDomains.isBlank()) {
            return List.of();
        }
        return Arrays.stream(includeDomains.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 无 API Key；仅依赖开关 */
    public boolean isConfigured() {
        return enabled;
    }
}
