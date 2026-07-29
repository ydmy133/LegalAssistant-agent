package com.legalassistant.websearch;

import lombok.Builder;
import lombok.Value;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析 DuckDuckGo HTML 搜索结果页。
 */
public final class DuckDuckGoResultParser {

    private DuckDuckGoResultParser() {
    }

    @Value
    @Builder
    public static class OrganicResult {
        String title;
        String url;
        String snippet;
    }

    public static List<OrganicResult> parse(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        List<OrganicResult> results = new ArrayList<>();

        // 经典 HTML 版：div.result / a.result__a
        for (org.jsoup.nodes.Element result : doc.select("div.result, div.web-result, div.results_links")) {
            org.jsoup.nodes.Element link = result.selectFirst("a.result__a, a.result-link, h2 a");
            if (link == null) {
                continue;
            }
            String href = resolveUrl(link.attr("href"));
            if (href == null || href.isBlank()) {
                continue;
            }
            String title = link.text();
            org.jsoup.nodes.Element snippetEl = result.selectFirst("a.result__snippet, div.result__snippet, td.result-snippet");
            String snippet = snippetEl != null ? snippetEl.text() : "";
            results.add(OrganicResult.builder()
                    .title(title)
                    .url(href)
                    .snippet(snippet)
                    .build());
        }

        // 兜底：仅有 result__a 列表
        if (results.isEmpty()) {
            for (org.jsoup.nodes.Element link : doc.select("a.result__a")) {
                String href = resolveUrl(link.attr("href"));
                if (href == null || href.isBlank()) {
                    continue;
                }
                results.add(OrganicResult.builder()
                        .title(link.text())
                        .url(href)
                        .snippet("")
                        .build());
            }
        }
        return results;
    }

    /**
     * DDG 有时把真实 URL 包在 //duckduckgo.com/l/?uddg=...
     */
    static String resolveUrl(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String raw = href.trim();
        if (raw.startsWith("//")) {
            raw = "https:" + raw;
        }
        try {
            java.net.URI uri = java.net.URI.create(raw);
            String host = uri.getHost();
            if (host != null && host.contains("duckduckgo.com") && uri.getQuery() != null) {
                for (String part : uri.getQuery().split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "uddg".equals(part.substring(0, eq))) {
                        return java.net.URLDecoder.decode(part.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            if (raw.startsWith("http://") || raw.startsWith("https://")) {
                return raw;
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }
}
