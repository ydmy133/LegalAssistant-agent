package com.legalassistant.retrieval;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class KnowledgeCandidate {
    String content;
    String sourceLabel;
    String url;
    KnowledgeSourceType sourceType;
    double sourceWeight;
    double relevanceScore;
    double rrfScore;
    double finalScore;
    int rankInSource;
}
