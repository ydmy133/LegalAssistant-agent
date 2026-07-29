package com.legalassistant.websearch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoResultParserTest {

    @Test
    void parsesTitleUrlSnippetAndUnwrapsUddg() throws IOException {
        String html = Files.readString(
                Path.of("src/test/resources/fixtures/ddg-search-results.html"),
                StandardCharsets.UTF_8);

        List<DuckDuckGoResultParser.OrganicResult> results = DuckDuckGoResultParser.parse(html);

        assertEquals(3, results.size());
        assertEquals("司法部：劳动合同法相关规定", results.get(0).getTitle());
        assertTrue(results.get(0).getUrl().contains("moj.gov.cn"));
        assertTrue(results.get(0).getSnippet().contains("二倍的工资"));

        assertTrue(results.get(1).getUrl().contains("court.gov.cn"));
        assertTrue(results.get(1).getTitle().contains("最高法"));
    }

    @Test
    void resolveUrlUnwrapsDuckDuckGoRedirect() {
        String wrapped = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.gov.cn%2Fzhengce%2Fcontent.htm&rut=x";
        assertEquals("https://www.gov.cn/zhengce/content.htm", DuckDuckGoResultParser.resolveUrl(wrapped));
    }
}
