package com.legalassistant.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从工具返回文案解析结构化来源（供 Thought 展开与正文引用兜底）。
 */
final class ToolResultSourceParser {

    private static final int MAX_SOURCES = 6;
    private static final int SNIPPET_MAX = 120;

    private static final Pattern BLOCK = Pattern.compile(
            "\\[来源:\\s*([^\\]]+)\\]\\s*\\n?([\\s\\S]*?)(?=\\n\\n\\[来源:|$)",
            Pattern.MULTILINE);

    private ToolResultSourceParser() {
    }

    static List<Map<String, Object>> parse(String toolResult) {
        List<Map<String, Object>> sources = new ArrayList<>();
        if (toolResult == null || toolResult.isBlank()) {
            return sources;
        }
        if (toolResult.startsWith("本轮已调用过") || toolResult.startsWith("本轮 searchLegalKnowledge")
                || toolResult.startsWith("联网已无命中") || toolResult.startsWith("联网搜索未启用")
                || toolResult.startsWith("本轮已有联网")) {
            return sources;
        }

        Matcher matcher = BLOCK.matcher(toolResult);
        while (matcher.find() && sources.size() < MAX_SOURCES) {
            String header = matcher.group(1).trim();
            String body = matcher.group(2) != null ? matcher.group(2).trim() : "";
            Map<String, Object> source = parseHeader(header, body);
            if (source != null) {
                sources.add(source);
            }
        }

        if (sources.isEmpty()) {
            // 判例块等：案号行
            Pattern casePat = Pattern.compile("案号[：:]\\s*([^\\n]+)");
            Matcher cm = casePat.matcher(toolResult);
            while (cm.find() && sources.size() < MAX_SOURCES) {
                Map<String, Object> src = new LinkedHashMap<>();
                src.put("kind", "case");
                src.put("title", cm.group(1).trim());
                src.put("snippet", truncate(cm.group(1).trim(), SNIPPET_MAX));
                sources.add(src);
            }
        }
        return sources;
    }

    static List<Map<String, Object>> mergeDedup(List<Map<String, Object>> thoughtSteps) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (thoughtSteps == null) {
            return merged;
        }
        for (Map<String, Object> step : thoughtSteps) {
            if (step == null || !"tool".equals(step.get("type"))) {
                continue;
            }
            Object raw = step.get("sources");
            if (!(raw instanceof List<?> list)) {
                continue;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> m)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> src = (Map<String, Object>) m;
                String key = sourceKey(src);
                if (key.isBlank() || !seen.add(key)) {
                    continue;
                }
                merged.add(new LinkedHashMap<>(src));
            }
        }
        return merged;
    }

    private static Map<String, Object> parseHeader(String header, String body) {
        String title = header;
        String url = null;
        String typeLabel = null;
        for (String part : header.split("\\|")) {
            String p = part.trim();
            if (p.startsWith("URL:")) {
                url = p.substring(4).trim();
            } else if (p.startsWith("类型:")) {
                typeLabel = p.substring(3).trim();
            } else if (!p.startsWith("得分:") && title.equals(header)) {
                title = p;
            }
        }
        // title 可能仍含「来源名」：取第一个非 URL/类型/得分段
        String[] parts = header.split("\\|");
        if (parts.length > 0) {
            title = parts[0].trim();
        }

        Map<String, Object> src = new LinkedHashMap<>();
        boolean isWeb = url != null && !url.isBlank()
                || (typeLabel != null && (typeLabel.contains("网页") || typeLabel.contains("权威")));
        if (isWeb && url != null && !url.isBlank()) {
            src.put("kind", "web");
            src.put("title", title);
            src.put("url", url);
            src.put("snippet", truncate(body, SNIPPET_MAX));
        } else {
            src.put("kind", "local");
            src.put("title", title);
            src.put("fileName", title);
            src.put("snippet", truncate(body, SNIPPET_MAX));
        }
        return src;
    }

    private static String sourceKey(Map<String, Object> src) {
        Object url = src.get("url");
        if (url != null && !String.valueOf(url).isBlank()) {
            return "u:" + url;
        }
        Object file = src.get("fileName");
        if (file != null && !String.valueOf(file).isBlank()) {
            return "f:" + file;
        }
        Object title = src.get("title");
        return title != null ? "t:" + title : "";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String n = text.replaceAll("\\s+", " ").trim();
        if (n.length() <= max) {
            return n;
        }
        return n.substring(0, max) + "...";
    }
}
