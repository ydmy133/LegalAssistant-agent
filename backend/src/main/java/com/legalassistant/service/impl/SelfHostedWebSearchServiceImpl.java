package com.legalassistant.service.impl;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.WebSearchHit;
import com.legalassistant.service.WebSearchService;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import com.legalassistant.websearch.CuratedLegalWebFallback;
import com.legalassistant.websearch.HtmlContentExtractor;
import com.legalassistant.websearch.SearxSearchClient;
import com.legalassistant.websearch.UrlSafety;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SelfHostedWebSearchServiceImpl implements WebSearchService {

    private final WebSearchProperties properties;
    private final SearxSearchClient searxSearchClient;
    private final CuratedLegalWebFallback curatedFallback;
    private final PageFetcher pageFetcher;
    private final AtomicReference<String> lastSearchError = new AtomicReference<>();

    @Autowired
    public SelfHostedWebSearchServiceImpl(WebSearchProperties properties,
                                          SearxSearchClient searxSearchClient,
                                          CuratedLegalWebFallback curatedFallback) {
        this(properties, searxSearchClient, curatedFallback, new JsoupPageFetcher(properties));
    }

    SelfHostedWebSearchServiceImpl(WebSearchProperties properties,
                                   SearxSearchClient searxSearchClient,
                                   CuratedLegalWebFallback curatedFallback,
                                   PageFetcher pageFetcher) {
        this.properties = properties;
        this.searxSearchClient = searxSearchClient;
        this.curatedFallback = curatedFallback;
        this.pageFetcher = pageFetcher;
    }

    @Override
    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /** 供工具层展示空结果原因 */
    public String lastErrorReason() {
        return lastSearchError.get();
    }

    @Override
    public List<WebSearchHit> search(String query) {
        lastSearchError.set(null);
        if (!isAvailable() || query == null || query.isBlank()) {
            lastSearchError.set("联网搜索未启用或查询为空");
            return List.of();
        }

        ChatTiming timing = ChatTimingRegistry.current();
        ChatTiming.Stage searxStage = timing != null
                ? timing.startChildStage("WebSearch.searxng", "query=" + truncate(query, 60))
                : null;

        List<SearxSearchClient.OrganicResult> organic = List.of();
        try {
            organic = searchViaSearx(query.trim());
            if (searxStage != null) {
                searxStage.end("hits=" + organic.size());
            }
        } catch (RuntimeException e) {
            log.warn("SearXNG search failed: {}", e.getMessage());
            lastSearchError.set("SearXNG 请求失败: " + e.getMessage());
            if (searxStage != null) {
                searxStage.end("error=" + e.getMessage());
            }
        }

        List<WebSearchHit> ranked = rankOrganic(organic);
        if (ranked.isEmpty() && properties.isCuratedFallback()) {
            ChatTiming.Stage curatedStage = timing != null
                    ? timing.startChildStage("WebSearch.curated", "fallback")
                    : null;
            List<WebSearchHit> curated = curatedFallback.matchAndFetch(query);
            if (curatedStage != null) {
                curatedStage.end("hits=" + curated.size());
            }
            if (!curated.isEmpty()) {
                lastSearchError.set(null);
                return limit(curated);
            }
            if (lastSearchError.get() == null) {
                lastSearchError.set("SearXNG 无命中，且 curated 官文法目录未匹配");
            }
            return List.of();
        }

        if (ranked.isEmpty()) {
            if (lastSearchError.get() == null) {
                lastSearchError.set("SearXNG 无命中");
            }
            return List.of();
        }

        int fetchLimit = Math.min(properties.getMaxFetchPages(), ranked.size());
        List<WebSearchHit> toFetch = ranked.subList(0, fetchLimit);
        List<WebSearchHit> remainder = ranked.size() > fetchLimit
                ? ranked.subList(fetchLimit, ranked.size())
                : List.of();

        ChatTiming.Stage fetchStage = timing != null
                ? timing.startChildStage("WebFetch.pages", "pages=" + toFetch.size())
                : null;
        List<WebSearchHit> fetched = fetchContents(toFetch);
        int ok = (int) fetched.stream()
                .filter(h -> h.getContent() != null && !h.getContent().isBlank())
                .count();
        if (fetchStage != null) {
            fetchStage.end("ok=" + ok + ", fail=" + (toFetch.size() - ok));
        }

        List<WebSearchHit> combined = new ArrayList<>(fetched);
        combined.addAll(remainder);
        return limit(combined);
    }

    private List<SearxSearchClient.OrganicResult> searchViaSearx(String query) {
        String constrained = buildConstrainedQuery(query);
        List<SearxSearchClient.OrganicResult> results =
                searxSearchClient.search(constrained, properties.getMaxResults() * 2);

        if (results.size() < 2 && properties.isPreferIncludeDomains()
                && !properties.includeDomainList().isEmpty()) {
            List<SearxSearchClient.OrganicResult> broad =
                    searxSearchClient.search(query, properties.getMaxResults() * 2);
            if (broad.size() > results.size()) {
                results = broad;
            }
        }
        return results;
    }

    String buildConstrainedQuery(String query) {
        if (!properties.isPreferIncludeDomains()) {
            return query;
        }
        List<String> domains = properties.includeDomainList();
        if (domains.isEmpty()) {
            return query;
        }
        // SearXNG 对复杂 OR site: 支持一般，取主域名加权即可
        return query + " site:" + domains.get(0);
    }

    List<WebSearchHit> rankOrganic(List<SearxSearchClient.OrganicResult> organic) {
        List<WebSearchHit> hits = new ArrayList<>();
        int rank = 1;
        for (SearxSearchClient.OrganicResult r : organic) {
            String domain = extractDomain(r.getUrl());
            KnowledgeSourceType type = classifySource(r.getUrl(), domain);
            hits.add(WebSearchHit.builder()
                    .title(r.getTitle())
                    .url(r.getUrl())
                    .snippet(r.getSnippet())
                    .content(r.getSnippet())
                    .domain(domain)
                    .sourceType(type)
                    .rank(rank++)
                    .build());
        }
        hits.sort(Comparator
                .comparingInt((WebSearchHit h) -> h.getSourceType() == KnowledgeSourceType.GOV ? 0 : 1)
                .thenComparingInt(WebSearchHit::getRank));
        List<WebSearchHit> renumbered = new ArrayList<>();
        int newRank = 1;
        for (WebSearchHit h : hits) {
            renumbered.add(WebSearchHit.builder()
                    .title(h.getTitle())
                    .url(h.getUrl())
                    .snippet(h.getSnippet())
                    .content(h.getContent())
                    .domain(h.getDomain())
                    .sourceType(h.getSourceType())
                    .rank(newRank++)
                    .build());
        }
        return renumbered;
    }

    private List<WebSearchHit> limit(List<WebSearchHit> hits) {
        int max = properties.getMaxResults();
        if (hits.size() > max) {
            return new ArrayList<>(hits.subList(0, max));
        }
        return hits;
    }

    private List<WebSearchHit> fetchContents(List<WebSearchHit> hits) {
        if (hits.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(hits.size(), 4));
        try {
            List<CompletableFuture<WebSearchHit>> futures = hits.stream()
                    .map(hit -> CompletableFuture.supplyAsync(() -> fetchOne(hit), pool))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            return futures.stream()
                    .map(f -> {
                        try {
                            return f.getNow(null);
                        } catch (Exception e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Parallel page fetch interrupted: {}", e.getMessage());
            return hits;
        } finally {
            pool.shutdownNow();
        }
    }

    private WebSearchHit fetchOne(WebSearchHit hit) {
        try {
            UrlSafety.assertSafePublicHttpUrl(hit.getUrl());
            String html = pageFetcher.fetchPageHtml(hit.getUrl());
            String content = HtmlContentExtractor.extract(html, properties.getMaxContentChars(), hit.getUrl());
            if (content == null || content.isBlank()) {
                content = hit.getSnippet();
            }
            return WebSearchHit.builder()
                    .title(hit.getTitle())
                    .url(hit.getUrl())
                    .snippet(hit.getSnippet())
                    .content(content)
                    .domain(hit.getDomain())
                    .sourceType(hit.getSourceType())
                    .rank(hit.getRank())
                    .build();
        } catch (Exception e) {
            log.debug("Fetch failed for {}: {}", hit.getUrl(), e.getMessage());
            return WebSearchHit.builder()
                    .title(hit.getTitle())
                    .url(hit.getUrl())
                    .snippet(hit.getSnippet())
                    .content(hit.getSnippet())
                    .domain(hit.getDomain())
                    .sourceType(hit.getSourceType())
                    .rank(hit.getRank())
                    .build();
        }
    }

    KnowledgeSourceType classifySource(String url, String domain) {
        String combined = ((url != null ? url : "") + " " + (domain != null ? domain : ""))
                .toLowerCase(Locale.ROOT);
        for (String govDomain : properties.includeDomainList()) {
            if (combined.contains(govDomain.toLowerCase(Locale.ROOT))) {
                return KnowledgeSourceType.GOV;
            }
        }
        if (combined.contains("gov.cn") || combined.contains("court.gov.cn")) {
            return KnowledgeSourceType.GOV;
        }
        return KnowledgeSourceType.WEB;
    }

    private static String extractDomain(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null ? uri.getHost() : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }

    interface PageFetcher {
        String fetchPageHtml(String url);
    }

    static final class JsoupPageFetcher implements PageFetcher {
        private final WebSearchProperties properties;

        JsoupPageFetcher(WebSearchProperties properties) {
            this.properties = properties;
        }

        @Override
        public String fetchPageHtml(String url) {
            UrlSafety.assertSafePublicHttpUrl(url);
            try {
                Connection.Response response = Jsoup.connect(url)
                        .userAgent(properties.getUserAgent())
                        .timeout(properties.getFetchTimeoutSeconds() * 1000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .ignoreContentType(true)
                        .execute();
                if (response.statusCode() >= 400) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                String contentType = response.contentType();
                if (contentType != null && !contentType.toLowerCase(Locale.ROOT).contains("html")
                        && !contentType.toLowerCase(Locale.ROOT).contains("text")) {
                    throw new IllegalStateException("non-html content-type=" + contentType);
                }
                return response.body();
            } catch (Exception e) {
                throw new IllegalStateException("page fetch failed: " + e.getMessage(), e);
            }
        }
    }
}
