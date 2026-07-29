package com.legalassistant.retrieval;

import com.legalassistant.config.WebSearchProperties;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalConfidenceEvaluatorTest {

    private LocalConfidenceEvaluator evaluator;

    @BeforeEach
    void setUp() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setEnabled(true);
        properties.setAutoFallback(true);
        properties.setMinLocalChars(200);
        evaluator = new LocalConfidenceEvaluator(properties);
    }

    @Test
    void needsWebFallbackWhenHitsZero() {
        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of())
                .rawText("未在知识库中找到相关内容。")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                .build();
        assertTrue(evaluator.needsWebFallback(outcome));
    }

    @Test
    void needsWebFallbackWhenKeywordFallback() {
        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of(TextSegment.from("短内容")))
                .rawText("短内容")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                .build();
        assertTrue(evaluator.needsWebFallback(outcome));
    }

    @Test
    void noWebFallbackWhenLightragHasAnyHitEvenIfShort() {
        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of(TextSegment.from("短")))
                .rawText("短")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG)
                .build();
        assertFalse(evaluator.needsWebFallback(outcome));
    }

    @Test
    void needsWebFallbackWhenLightragFailed() {
        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of())
                .rawText("关键词回退内容")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                .lightragFailed(true)
                .build();
        assertTrue(evaluator.needsWebFallback(outcome));
    }

    @Test
    void noWebFallbackWhenLightragHitsAreSufficient() {
        String longText = ("《劳动合同法》第八十二条：用人单位自用工之日起超过一个月不满一年未与劳动者订立书面劳动合同的，"
                + "应当向劳动者每月支付二倍的工资。满一年未签书面劳动合同的，视为已订立无固定期限劳动合同。").repeat(5);
        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of(TextSegment.from(longText)))
                .rawText(longText)
                .source(LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG)
                .build();
        assertFalse(evaluator.needsWebFallback(outcome));
    }

    @Test
    void noWebFallbackWhenDisabled() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setEnabled(false);
        evaluator = new LocalConfidenceEvaluator(properties);

        LocalRetrievalOutcome outcome = LocalRetrievalOutcome.builder()
                .segments(List.of())
                .rawText("未在知识库中找到相关内容。")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                .build();
        assertFalse(evaluator.needsWebFallback(outcome));
    }

    @Test
    void containsNotFoundMessageDetectsFixedText() {
        assertTrue(LocalConfidenceEvaluator.containsNotFoundMessage("未在知识库中找到相关内容。"));
        assertFalse(LocalConfidenceEvaluator.containsNotFoundMessage("第八十二条 双倍工资"));
    }
}
