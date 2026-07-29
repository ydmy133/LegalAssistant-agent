package com.legalassistant.retrieval;

import com.legalassistant.config.WebSearchProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LocalConfidenceEvaluator {

    private static final String NOT_FOUND_MESSAGE = "未在知识库中找到相关内容";

    private final WebSearchProperties webSearchProperties;

    public boolean needsWebFallback(LocalRetrievalOutcome outcome) {
        if (!webSearchProperties.isAutoFallback() || !webSearchProperties.isConfigured()) {
            return false;
        }
        if (outcome == null) {
            return true;
        }
        if (outcome.isLightragFailed()) {
            return true;
        }
        if (outcome.getSource() == LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG
                && outcome.hitCount() > 0) {
            return false;
        }
        if (outcome.getSource() == LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK) {
            return true;
        }
        if (outcome.hitCount() == 0) {
            return true;
        }
        if (isInsufficientText(outcome.getRawText())) {
            return true;
        }
        return containsNotFoundMessage(outcome.getRawText());
    }

    boolean isInsufficientText(String text) {
        int length = effectiveLength(text);
        return length < webSearchProperties.getMinLocalChars();
    }

    static boolean containsNotFoundMessage(String text) {
        return text != null && text.contains(NOT_FOUND_MESSAGE);
    }

    private static int effectiveLength(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.replaceAll("\\s+", "").length();
    }
}
