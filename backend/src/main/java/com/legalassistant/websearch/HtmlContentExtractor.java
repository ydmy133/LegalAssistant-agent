package com.legalassistant.websearch;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * 从 HTML 页面抽取可读正文（对标 Cursor Fetch 的可读文本）。
 */
public final class HtmlContentExtractor {

    private static final String[] PREFERRED_SELECTORS = {
            "article",
            "main",
            "#content",
            ".content",
            "#main",
            ".main-content",
            ".article-content",
            ".TRS_Editor"
    };

    private HtmlContentExtractor() {
    }

    public static String extract(String html, int maxChars) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, nav, header, footer, iframe, svg").remove();

        Element preferred = null;
        for (String selector : PREFERRED_SELECTORS) {
            preferred = doc.selectFirst(selector);
            if (preferred != null && preferred.text().trim().length() >= 40) {
                break;
            }
            preferred = null;
        }
        Element root = preferred != null ? preferred : doc.body();
        if (root == null) {
            return "";
        }

        String text = Jsoup.clean(root.html(), Safelist.none());
        text = text.replace('\u00a0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars) + "...";
        }
        return text;
    }
}
