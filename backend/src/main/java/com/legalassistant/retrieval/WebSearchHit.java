package com.legalassistant.retrieval;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WebSearchHit {
    String title;
    String url;
    String snippet;
    String content;
    String domain;
    KnowledgeSourceType sourceType;
    int rank;

    public String displayText() {
        String body = content != null && !content.isBlank() ? content : snippet;
        if (body == null || body.isBlank()) {
            body = title != null ? title : "";
        }
        return body;
    }
}
