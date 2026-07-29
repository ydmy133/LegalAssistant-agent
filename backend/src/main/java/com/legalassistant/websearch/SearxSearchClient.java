package com.legalassistant.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalassistant.config.WebSearchProperties;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class SearxSearchClient {

    private final WebSearchProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value
    @Builder
    public static class OrganicResult {
        String title;
        String url;
        String snippet;
    }

    public List<OrganicResult> search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String base = properties.getSearxngBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("searxng-base-url 未配置");
        }
        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String url = base.replaceAll("/$", "") + "/search?q=" + encoded
                    + "&format=json&language=zh-CN";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(Math.max(5, properties.getTimeoutSeconds())))
                    .header("User-Agent", properties.getUserAgent())
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("SearXNG HTTP " + response.statusCode());
            }
            return parseJson(response.body(), maxResults);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SearXNG interrupted: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("SearXNG request failed: " + e.getMessage(), e);
        }
    }

    List<OrganicResult> parseJson(String body, int maxResults) throws Exception {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(body);
        JsonNode results = root.get("results");
        if (results == null || !results.isArray()) {
            return List.of();
        }
        List<OrganicResult> out = new ArrayList<>();
        for (JsonNode node : results) {
            if (out.size() >= maxResults) {
                break;
            }
            String url = text(node, "url");
            String title = text(node, "title");
            if (url == null || url.isBlank()) {
                continue;
            }
            String snippet = text(node, "content");
            if (snippet == null || snippet.isBlank()) {
                snippet = text(node, "snippet");
            }
            out.add(OrganicResult.builder()
                    .title(title != null ? title : url)
                    .url(url)
                    .snippet(snippet != null ? snippet : "")
                    .build());
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
