package com.legalassistant.agent;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.mapper.ConversationMapper;
import com.legalassistant.mapper.LegalCaseMapper;
import com.legalassistant.mapper.MessageMapper;
import com.legalassistant.retrieval.LocalConfidenceEvaluator;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.WebSearchHit;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.KnowledgeFusionService;
import com.legalassistant.service.RAGService;
import com.legalassistant.service.WebSearchService;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LegalToolsWebFallbackTest {

    @Mock
    private RAGService ragService;
    @Mock
    private DocumentService documentService;
    @Mock
    private WebSearchService webSearchService;
    @Mock
    private KnowledgeFusionService knowledgeFusionService;
    @Mock
    private LegalCaseMapper caseMapper;
    @Mock
    private MessageMapper messageMapper;
    @Mock
    private ConversationMapper conversationMapper;

    private LegalTools legalTools;

    @BeforeEach
    void setUp() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setEnabled(true);
        properties.setAutoFallback(true);
        properties.setMinLocalChars(200);
        LocalConfidenceEvaluator evaluator = new LocalConfidenceEvaluator(properties);

        legalTools = new LegalTools(
                ragService,
                documentService,
                webSearchService,
                evaluator,
                knowledgeFusionService,
                new SessionToolGuard(),
                caseMapper,
                messageMapper,
                conversationMapper
        );
    }

    @Test
    void searchLegalKnowledgeTriggersWebFallbackWhenLocalEmpty() {
        when(ragService.search(eq("最新修订"), eq(null))).thenReturn(List.of());
        when(documentService.searchPresetDocumentsByKeyword("最新修订"))
                .thenReturn("未在知识库中找到相关内容。");
        when(webSearchService.search("最新修订")).thenReturn(List.of(
                WebSearchHit.builder()
                        .title("人社部")
                        .url("https://www.moj.gov.cn/example")
                        .content("2024年配套规定")
                        .sourceType(KnowledgeSourceType.GOV)
                        .rank(1)
                        .build()
        ));
        when(knowledgeFusionService.fuse(eq("最新修订"), any(), any()))
                .thenReturn("[来源: 人社部 | 类型: 权威网页 | URL: https://www.moj.gov.cn/example]\n2024年配套规定");
        when(caseMapper.selectList(any())).thenReturn(List.of());

        String result = legalTools.searchLegalKnowledge("session-1", "最新修订");

        verify(webSearchService).search("最新修订");
        assertTrue(result.contains("https://www.moj.gov.cn/example"));
    }

    @Test
    void searchLegalKnowledgeSkipsWebFallbackWhenLocalHitsAreStrong() {
        TextSegment segment = TextSegment.from("《劳动合同法》第八十二条 双倍工资 ".repeat(20));
        when(ragService.search(eq("未签劳动合同"), eq(null))).thenReturn(List.of(segment));
        when(caseMapper.selectList(any())).thenReturn(List.of());

        String result = legalTools.searchLegalKnowledge("session-2", "未签劳动合同");

        verify(webSearchService, org.mockito.Mockito.never()).search(any());
        assertTrue(result.contains("第八十二条"));
    }

    @Test
    void searchWebReturnsDisabledMessageWhenUnavailable() {
        when(webSearchService.isAvailable()).thenReturn(false);

        String result = legalTools.searchWeb("session-3", "最新修订");

        assertTrue(result.contains("未启用"));
    }

    @Test
    void searchWebEmptyIncludesReason() {
        when(webSearchService.isAvailable()).thenReturn(true);
        when(webSearchService.search("无人问津的词")).thenReturn(List.of());
        when(knowledgeFusionService.formatWebHits(any(), any()))
                .thenReturn("联网检索未找到相关内容（SearXNG 无命中）。请基于已有信息作答，勿换词重复联网。");

        String result = legalTools.searchWeb("session-4", "无人问津的词");

        assertTrue(result.contains("未找到") || result.contains("SearXNG"));
    }
}
