package com.legalassistant.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class LocalRetrievalOutcome {
    List<TextSegment> segments;
    String rawText;
    LocalRetrievalSource source;
    String errorMessage;
    boolean lightragFailed;

    public int hitCount() {
        return segments != null ? segments.size() : 0;
    }

    public enum LocalRetrievalSource {
        LIGHTRAG,
        KEYWORD_FALLBACK
    }
}
