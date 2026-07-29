package com.legalassistant.service.impl;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.LocalRetrievalOutcome;
import com.legalassistant.retrieval.WebSearchHit;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeFusionServiceImplTest {

    private KnowledgeFusionServiceImpl fusionService;

    @BeforeEach
    void setUp() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setMaxFusedSegments(4);
        WebSearchProperties.SourceWeights weights = new WebSearchProperties.SourceWeights();
        weights.setLocal(1.0);
        weights.setGov(0.9);
        weights.setWeb(0.65);
        properties.setSourceWeights(weights);
        fusionService = new KnowledgeFusionServiceImpl(properties);
    }

    @Test
    void fusePrefersLocalCandidatesWithinQuota() {
        List<TextSegment> localSegments = List.of(
                TextSegment.from("《劳动合同法》第八十二条 双倍工资"),
                TextSegment.from("《劳动合同法》第十四条第三款 无固定期限")
        );
        LocalRetrievalOutcome local = LocalRetrievalOutcome.builder()
                .segments(localSegments)
                .rawText("local")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG)
                .build();

        List<WebSearchHit> webHits = List.of(
                WebSearchHit.builder()
                        .title("普通网页")
                        .url("https://example.com/a")
                        .content("网页内容A")
                        .sourceType(KnowledgeSourceType.WEB)
                        .rank(1)
                        .build(),
                WebSearchHit.builder()
                        .title("政府网页")
                        .url("https://www.gov.cn/example")
                        .content("政府内容")
                        .sourceType(KnowledgeSourceType.GOV)
                        .rank(2)
                        .build()
        );

        String fused = fusionService.fuse("未签劳动合同 双倍工资", local, webHits);
        assertTrue(fused.contains("本地库"));
        assertTrue(fused.contains("第八十二条"));
        assertTrue(fused.contains("URL: https://www.gov.cn/example"));
    }

    @Test
    void fuseDedupesAndCapsSegments() {
        LocalRetrievalOutcome local = LocalRetrievalOutcome.builder()
                .segments(List.of(TextSegment.from("重复内容")))
                .rawText("重复内容")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.LIGHTRAG)
                .build();

        List<WebSearchHit> webHits = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            webHits.add(WebSearchHit.builder()
                    .title("网页" + i)
                    .url("https://example.com/" + i)
                    .content("网页内容" + i)
                    .sourceType(KnowledgeSourceType.WEB)
                    .rank(i)
                    .build());
        }

        String fused = fusionService.fuse("劳动合同", local, webHits);
        long segmentCount = fused.lines().filter(line -> line.startsWith("[来源:")).count();
        assertTrue(segmentCount <= 4);
    }

    @Test
    void fuseIncludesGovUrlWhenPresent() {
        LocalRetrievalOutcome local = LocalRetrievalOutcome.builder()
                .segments(List.of())
                .rawText("未在知识库中找到相关内容。")
                .source(LocalRetrievalOutcome.LocalRetrievalSource.KEYWORD_FALLBACK)
                .build();

        List<WebSearchHit> webHits = List.of(
                WebSearchHit.builder()
                        .title("blog")
                        .url("https://example.com/blog")
                        .content("博客内容")
                        .sourceType(KnowledgeSourceType.WEB)
                        .rank(1)
                        .build(),
                WebSearchHit.builder()
                        .title("gov")
                        .url("https://www.moj.gov.cn/rule")
                        .content("仲裁时效规定")
                        .sourceType(KnowledgeSourceType.GOV)
                        .rank(2)
                        .build()
        );

        String fused = fusionService.fuse("北京市 仲裁时效", local, webHits);
        assertTrue(fused.contains("权威网页"));
        assertTrue(fused.contains("URL: https://www.moj.gov.cn/rule"));
    }
}
