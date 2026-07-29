package com.legalassistant.service.impl;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.WebSearchHit;
import com.legalassistant.websearch.CuratedLegalWebFallback;
import com.legalassistant.websearch.SearxSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SelfHostedWebSearchServiceImplTest {

    @Mock
    private SearxSearchClient searxSearchClient;
    @Mock
    private CuratedLegalWebFallback curatedFallback;

    private WebSearchProperties properties;
    private String articleFixture;

    @BeforeEach
    void setUp() throws Exception {
        properties = new WebSearchProperties();
        properties.setEnabled(true);
        properties.setMaxResults(5);
        properties.setMaxFetchPages(2);
        properties.setMaxContentChars(3000);
        properties.setPreferIncludeDomains(true);
        properties.setIncludeDomains("gov.cn,court.gov.cn,moj.gov.cn");
        properties.setCuratedFallback(true);
        articleFixture = Files.readString(
                Path.of("src/test/resources/fixtures/article-page.html"),
                StandardCharsets.UTF_8);
    }

    @Test
    void disabledServiceIsUnavailable() {
        properties.setEnabled(false);
        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, new RecordingFetcher());
        assertFalse(service.isAvailable());
        assertTrue(service.search("劳动合同").isEmpty());
    }

    @Test
    void searchRanksGovFirstAndFetchesContent() {
        when(searxSearchClient.search(anyString(), anyInt())).thenReturn(List.of(
                SearxSearchClient.OrganicResult.builder()
                        .title("博客")
                        .url("https://example.com/blog")
                        .snippet("非权威")
                        .build(),
                SearxSearchClient.OrganicResult.builder()
                        .title("司法部")
                        .url("https://www.moj.gov.cn/x")
                        .snippet("劳动合同")
                        .build(),
                SearxSearchClient.OrganicResult.builder()
                        .title("法院")
                        .url("https://www.court.gov.cn/y")
                        .snippet("双倍工资")
                        .build()
        ));

        RecordingFetcher fetcher = new RecordingFetcher();
        fetcher.pageHtmlByHost.put("www.moj.gov.cn", articleFixture);
        fetcher.pageHtmlByHost.put("www.court.gov.cn", articleFixture);
        fetcher.pageHtmlByHost.put("example.com", "<html><body>blog</body></html>");

        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, fetcher);

        List<WebSearchHit> hits = service.search("未签劳动合同 双倍工资");

        assertFalse(hits.isEmpty());
        assertEquals(KnowledgeSourceType.GOV, hits.get(0).getSourceType());
        assertTrue(hits.get(0).getUrl().contains("gov.cn") || hits.get(0).getUrl().contains("court.gov.cn"));
        assertTrue(hits.get(0).getContent() != null && hits.get(0).getContent().contains("第八十二条"));
        assertTrue(fetcher.pageCalls.get() <= properties.getMaxFetchPages());
    }

    @Test
    void emptySearxUsesCuratedFallback() {
        when(searxSearchClient.search(anyString(), anyInt())).thenReturn(List.of());
        when(curatedFallback.matchAndFetch("主播 劳动关系")).thenReturn(List.of(
                WebSearchHit.builder()
                        .title("新就业形态指导意见")
                        .url("https://www.gov.cn/example")
                        .content("主播等新业态")
                        .sourceType(KnowledgeSourceType.GOV)
                        .rank(1)
                        .build()
        ));

        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, new RecordingFetcher());

        List<WebSearchHit> hits = service.search("主播 劳动关系");
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getUrl().contains("gov.cn"));
    }

    @Test
    void emptyResultSetsErrorReason() {
        when(searxSearchClient.search(anyString(), anyInt())).thenReturn(List.of());
        when(curatedFallback.matchAndFetch(anyString())).thenReturn(List.of());

        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, new RecordingFetcher());

        assertTrue(service.search("无关词 xyz").isEmpty());
        assertTrue(service.lastErrorReason() != null && service.lastErrorReason().contains("SearXNG"));
    }

    @Test
    void buildConstrainedQueryAddsSiteFilter() {
        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, new RecordingFetcher());
        String q = service.buildConstrainedQuery("仲裁时效");
        assertTrue(q.contains("site:gov.cn"));
    }

    @Test
    void classifyGovAndWeb() {
        SelfHostedWebSearchServiceImpl service =
                new SelfHostedWebSearchServiceImpl(properties, searxSearchClient, curatedFallback, new RecordingFetcher());
        assertEquals(KnowledgeSourceType.GOV,
                service.classifySource("https://www.moj.gov.cn/x", "www.moj.gov.cn"));
        assertEquals(KnowledgeSourceType.WEB,
                service.classifySource("https://example.com/x", "example.com"));
    }

    static final class RecordingFetcher implements SelfHostedWebSearchServiceImpl.PageFetcher {
        final Map<String, String> pageHtmlByHost = new ConcurrentHashMap<>();
        final AtomicInteger pageCalls = new AtomicInteger();

        @Override
        public String fetchPageHtml(String url) {
            pageCalls.incrementAndGet();
            String host;
            try {
                host = java.net.URI.create(url).getHost();
            } catch (Exception e) {
                throw new IllegalStateException("bad url");
            }
            String html = pageHtmlByHost.get(host);
            if (html == null) {
                throw new IllegalStateException("no fixture for " + host);
            }
            return html;
        }
    }
}
