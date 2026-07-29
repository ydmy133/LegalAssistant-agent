package com.legalassistant.websearch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalassistant.config.WebSearchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxSearchClientTest {

    private SearxSearchClient client;

    @BeforeEach
    void setUp() {
        WebSearchProperties properties = new WebSearchProperties();
        properties.setSearxngBaseUrl("http://localhost:8088");
        client = new SearxSearchClient(properties, new ObjectMapper());
    }

    @Test
    void parseJsonExtractsTitleUrlContent() throws Exception {
        String fixture = Files.readString(
                Path.of("src/test/resources/fixtures/searx-search-results.json"),
                StandardCharsets.UTF_8);

        List<SearxSearchClient.OrganicResult> results = client.parseJson(fixture, 5);

        assertEquals(2, results.size());
        assertEquals("人社部指导意见", results.get(0).getTitle());
        assertTrue(results.get(0).getUrl().contains("gov.cn"));
        assertTrue(results.get(0).getSnippet().contains("新就业形态"));
        assertEquals("最高法案例", results.get(1).getTitle());
    }

    @Test
    void parseJsonSkipsEntriesWithoutUrl() throws Exception {
        String body = """
                {"results":[{"title":"无链接","content":"x"},{"title":"有","url":"https://a.gov.cn/x","content":"y"}]}
                """;
        List<SearxSearchClient.OrganicResult> results = client.parseJson(body, 10);
        assertEquals(1, results.size());
        assertEquals("有", results.get(0).getTitle());
    }
}
