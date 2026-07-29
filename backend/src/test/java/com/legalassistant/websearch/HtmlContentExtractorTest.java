package com.legalassistant.websearch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlContentExtractorTest {

    @Test
    void extractsArticleBodyAndStripsNoise() throws IOException {
        String html = Files.readString(
                Path.of("src/test/resources/fixtures/article-page.html"),
                StandardCharsets.UTF_8);

        String text = HtmlContentExtractor.extract(html, 3000);

        assertTrue(text.contains("第八十二条"));
        assertTrue(text.contains("二倍的工资"));
        assertFalse(text.contains("alert"));
        assertFalse(text.contains("color:red"));
        assertFalse(text.contains("网站导航"));
    }

    @Test
    void truncatesToMaxChars() throws IOException {
        String html = Files.readString(
                Path.of("src/test/resources/fixtures/article-page.html"),
                StandardCharsets.UTF_8);

        String text = HtmlContentExtractor.extract(html, 40);
        assertTrue(text.length() <= 43); // 40 + "..."
        assertTrue(text.endsWith("..."));
    }
}
