package com.legalassistant.websearch;

import com.legalassistant.config.WebSearchProperties;
import com.legalassistant.retrieval.KnowledgeSourceType;
import com.legalassistant.retrieval.WebSearchHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 当元搜索无命中时，按关键词匹配预置官方法规 URL 并抓取正文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CuratedLegalWebFallback {

    private final WebSearchProperties properties;

    private record CuratedDoc(String[] keywords, String title, String url) {
    }

    private static final List<CuratedDoc> CATALOG = List.of(
            new CuratedDoc(
                    new String[]{"新就业形态", "新业态", "主播", "平台用工", "56号", "人社部发"},
                    "关于维护新就业形态劳动者劳动保障权益的指导意见（人社部发〔2021〕56号）",
                    "https://www.gov.cn/zhengce/zhengceku/2021-07/23/content_5626761.htm"
            ),
            new CuratedDoc(
                    new String[]{"劳动合同法", "书面劳动合同", "双倍工资", "未签"},
                    "中华人民共和国劳动合同法（中央人民政府）",
                    "https://www.gov.cn/banshi/2005-08/05/content_20688.htm"
            ),
            new CuratedDoc(
                    new String[]{"劳动争议", "仲裁", "调解"},
                    "中华人民共和国劳动争议调解仲裁法",
                    "https://www.gov.cn/flfg/2007-12/29/content_847113.htm"
            )
    );

    public List<WebSearchHit> matchAndFetch(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<WebSearchHit> hits = new ArrayList<>();
        int rank = 1;
        for (CuratedDoc doc : CATALOG) {
            if (!matches(q, doc.keywords())) {
                continue;
            }
            String content = fetchContent(doc.url());
            String domain = extractDomain(doc.url());
            hits.add(WebSearchHit.builder()
                    .title(doc.title())
                    .url(doc.url())
                    .snippet(doc.title())
                    .content(content != null && !content.isBlank() ? content : doc.title())
                    .domain(domain)
                    .sourceType(KnowledgeSourceType.GOV)
                    .rank(rank++)
                    .build());
            if (hits.size() >= 2) {
                break;
            }
        }
        return hits;
    }

    private static boolean matches(String queryLower, String[] keywords) {
        String compact = queryLower.replace(" ", "");
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && compact.contains(kw.toLowerCase(Locale.ROOT).replace(" ", ""))) {
                return true;
            }
        }
        return false;
    }

    private String fetchContent(String url) {
        try {
            UrlSafety.assertSafePublicHttpUrl(url);
            Connection.Response response = Jsoup.connect(url)
                    .userAgent(properties.getUserAgent())
                    .timeout(properties.getFetchTimeoutSeconds() * 1000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .execute();
            if (response.statusCode() >= 400) {
                log.debug("Curated fetch HTTP {} for {}", response.statusCode(), url);
                return null;
            }
            return HtmlContentExtractor.extract(response.body(), properties.getMaxContentChars(), url);
        } catch (Exception e) {
            log.debug("Curated fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    private static String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() != null ? uri.getHost() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
