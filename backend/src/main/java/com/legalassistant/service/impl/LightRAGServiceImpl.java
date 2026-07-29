package com.legalassistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalassistant.client.LightRAGClient;
import com.legalassistant.config.LightRAGProperties;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.RAGService;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LightRAGServiceImpl implements RAGService {

    private final LightRAGClient lightRAGClient;
    private final LightRAGProperties properties;
    private final DocumentMapper documentMapper;

    private final ConcurrentHashMap<String, CacheEntry> queryCache = new ConcurrentHashMap<>();

    private record CacheEntry(List<TextSegment> segments, long expireAtMs) {
    }

    @Override
    public void ingestDocument(Long documentId, byte[] fileBytes, String fileName, Long modelConfigId) {
        String fileSource = buildFileSource(documentId, fileName);
        log.info("Ingesting document {} into LightRAG: {}", documentId, fileSource);

        String trackId = lightRAGClient.uploadDocument(fileBytes, fileName, fileSource);
        lightRAGClient.pollUntilDone(trackId);

        int chunkCount = lightRAGClient.countChunksByFileSource(fileSource);
        if (chunkCount <= 0) {
            chunkCount = 1;
        }

        var doc = documentMapper.selectById(documentId);
        if (doc != null) {
            doc.setChunkCount(chunkCount);
            documentMapper.updateById(doc);
        }
        queryCache.clear();
        log.info("Document {} indexed in LightRAG with {} chunk(s)", documentId, chunkCount);
    }

    @Override
    public List<TextSegment> search(String query, Long modelConfigId) {
        ChatTiming timing = ChatTimingRegistry.current();
        String mode = properties.getQueryMode();
        List<String> hlKeywords = null;
        List<String> llKeywords = null;
        if (properties.isProvideKeywords()) {
            hlKeywords = extractHighLevelKeywords(query);
            llKeywords = extractLowLevelKeywords(query);
        }

        String cacheKey = buildCacheKey(query, mode, hlKeywords);
        List<TextSegment> cached = getFromCache(cacheKey);
        if (cached != null) {
            if (timing != null) {
                ChatTiming.Stage cacheStage = timing.startChildStage("LightRAG.cacheHit", cacheKey);
                cacheStage.end("segments=" + cached.size());
            }
            return cached;
        }

        String detail = "mode=" + mode
                + ", top_k=" + properties.getTopK()
                + ", rerank=" + properties.isEnableRerank()
                + (hlKeywords != null ? ", hl=" + hlKeywords.size() : "");
        ChatTiming.Stage httpStage = timing != null
                ? timing.startChildStage("LightRAG.queryData", detail)
                : null;
        JsonNode response;
        try {
            response = lightRAGClient.queryData(query, mode, hlKeywords, llKeywords);
            if (httpStage != null) {
                httpStage.end("ok");
            }
        } catch (RuntimeException e) {
            if (httpStage != null) {
                httpStage.end("error=" + e.getMessage());
            }
            throw e;
        }

        ChatTiming.Stage formatStage = timing != null
                ? timing.startChildStage("LightRAG.format", "chunks>entities>relations")
                : null;
        try {
            JsonNode data = response.get("data");
            if (data == null || data.isNull()) {
                if (formatStage != null) {
                    formatStage.end("empty");
                }
                return List.of();
            }
            List<TextSegment> segments = formatQueryData(data);
            int beforeCap = segments.size();
            if (segments.size() > properties.getMaxSegments()) {
                segments = new ArrayList<>(segments.subList(0, properties.getMaxSegments()));
            }
            putCache(cacheKey, segments);
            if (formatStage != null) {
                int entities = sizeOf(data.get("entities"));
                int relations = sizeOf(data.get("relationships"));
                int chunks = sizeOf(data.get("chunks"));
                formatStage.end(String.format(
                        "segments=%d(capped from %d), entities=%d, relations=%d, chunks=%d",
                        segments.size(), beforeCap, entities, relations, chunks));
            }
            return segments;
        } catch (RuntimeException e) {
            if (formatStage != null) {
                formatStage.end("error=" + e.getMessage());
            }
            throw e;
        }
    }

    private List<TextSegment> getFromCache(String key) {
        if (properties.getQueryCacheTtlSeconds() <= 0) {
            return null;
        }
        CacheEntry entry = queryCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAtMs()) {
            queryCache.remove(key, entry);
            return null;
        }
        return new ArrayList<>(entry.segments());
    }

    private void putCache(String key, List<TextSegment> segments) {
        long ttl = properties.getQueryCacheTtlSeconds();
        if (ttl <= 0 || segments == null || segments.isEmpty() || key == null || key.isBlank()) {
            return;
        }
        queryCache.put(key, new CacheEntry(List.copyOf(segments),
                System.currentTimeMillis() + ttl * 1000L));
        // 容量超限时只剔除已过期项；仍过多则按过期时间淘汰最旧一半，避免整表清空导致冷路径回升
        if (queryCache.size() > 256) {
            long now = System.currentTimeMillis();
            queryCache.entrySet().removeIf(e -> now > e.getValue().expireAtMs());
            if (queryCache.size() > 256) {
                queryCache.entrySet().stream()
                        .sorted(Comparator.comparingLong(e -> e.getValue().expireAtMs()))
                        .limit(queryCache.size() / 2)
                        .map(Map.Entry::getKey)
                        .toList()
                        .forEach(queryCache::remove);
            }
        }
    }

    /**
     * 语义级缓存键：优先用稳定 HL 关键词指纹，避免 Agent 改写 query / LL n-gram 导致次次 miss。
     */
    private String buildCacheKey(String query, String mode, List<String> hl) {
        String cfg = "|tk=" + properties.getTopK() + "|ck=" + properties.getChunkTopK();
        if (hl != null && !hl.isEmpty()) {
            String fp = hl.stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .sorted()
                    .collect(Collectors.joining(","));
            if (!fp.isEmpty()) {
                return mode + "|sem=" + fp + cfg;
            }
        }
        return mode + "|q=" + normalizeQuery(query) + cfg;
    }

    static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String q = query
                .replace("签订", "签")
                .replace("书面劳动合同", "劳动合同")
                .replaceAll("[\\p{Punct}\\s？?！!。，、；;：:（）()【】\\[\\]《》]+", "")
                .trim();
        return q.length() > 48 ? q.substring(0, 48) : q;
    }

    static List<String> extractHighLevelKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String q = query.replaceAll("[\\p{Punct}\\s？?！!。，、；;：:（）()【】\\[\\]《》]+", " ").trim();
        String[] phrases = {
                "未签订书面劳动合同", "未签劳动合同", "未签订劳动合同", "双倍工资",
                "无固定期限劳动合同", "劳动合同法", "劳动争议", "书面劳动合同",
                "经济补偿", "违法解除", "试用期", "加班费", "第十四条第三款", "第八十二条"
        };
        for (String p : phrases) {
            if (q.contains(p) || containsLoose(q, p)) {
                out.add(p);
            }
        }
        if (q.contains("未签") && !out.contains("未签订书面劳动合同")) {
            out.add("未签订书面劳动合同");
            out.add("未签劳动合同");
        }
        if (q.contains("双倍")) {
            out.add("双倍工资");
        }
        if (out.isEmpty() && q.length() >= 4) {
            out.add(q.length() > 16 ? q.substring(0, 16) : q);
        }
        return new ArrayList<>(out);
    }

    static List<String> extractLowLevelKeywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String q = query.replaceAll("[\\p{Punct}\\s？?！!。，、；;：:（）()【】\\[\\]《》]+", "");
        String[] terms = {"劳动合同", "双倍工资", "无固定期限", "书面合同", "第八十二条",
                "第十四条", "第三款", "第七条", "仲裁", "用工"};
        for (String t : terms) {
            if (q.contains(t)) {
                out.add(t);
            }
        }
        int added = 0;
        for (int len = 4; len >= 2 && added < 6; len--) {
            for (int i = 0; i + len <= q.length() && added < 6; i++) {
                String gram = q.substring(i, i + len);
                if (gram.chars().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                    out.add(gram);
                    added++;
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean containsLoose(String query, String phrase) {
        String compactQ = query.replace("订", "").replace("书面", "");
        String compactP = phrase.replace("订", "").replace("书面", "");
        return compactQ.contains(compactP) || compactP.contains(compactQ);
    }

    private static int sizeOf(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }

    @Override
    public void deleteDocumentEmbeddings(Long documentId) {
        String prefix = properties.getFileSourcePrefix() + ":" + documentId + ":";
        List<String> docIds = lightRAGClient.findDocIdsByFileSource(prefix);
        lightRAGClient.deleteByDocIds(docIds);
        queryCache.clear();
        log.info("Deleted LightRAG embeddings for document {} ({} doc id(s))", documentId, docIds.size());
    }

    public String buildFileSource(Long documentId, String fileName) {
        return properties.getFileSourcePrefix() + ":" + documentId + ":" + fileName;
    }

    public boolean existsByFileSource(String fileSource) {
        return lightRAGClient.existsByFileSource(fileSource);
    }

    /**
     * chunks 优先，再实体（偏法条描述），再关系；各自有配额后再受 maxSegments 总封顶。
     */
    private List<TextSegment> formatQueryData(JsonNode data) {
        List<TextSegment> chunks = new ArrayList<>();
        List<TextSegment> entities = new ArrayList<>();
        List<TextSegment> relations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        appendChunkSegments(data.get("chunks"), chunks, seen, properties.getMaxChunkSegments());
        appendEntitySegments(data.get("entities"), entities, seen, properties.getMaxEntitySegments());
        appendRelationshipSegments(data.get("relationships"), relations, seen, properties.getMaxRelationSegments());

        List<TextSegment> segments = new ArrayList<>(chunks.size() + entities.size() + relations.size());
        segments.addAll(chunks);
        segments.addAll(entities);
        segments.addAll(relations);
        return segments;
    }

    private void appendEntitySegments(JsonNode entities, List<TextSegment> segments, Set<String> seen, int limit) {
        if (entities == null || !entities.isArray() || limit <= 0) {
            return;
        }
        // 优先含「条」字的描述（更像法条）
        List<JsonNode> preferred = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode entity : entities) {
            String description = text(entity, "description");
            if (description == null || description.isBlank()) {
                continue;
            }
            if (description.contains("条") || description.contains("劳动合同")) {
                preferred.add(entity);
            } else {
                others.add(entity);
            }
        }
        List<JsonNode> ordered = new ArrayList<>(preferred);
        ordered.addAll(others);
        for (JsonNode entity : ordered) {
            if (segments.size() >= limit) {
                break;
            }
            String name = text(entity, "entity_name");
            String type = text(entity, "entity_type");
            String description = text(entity, "description");
            String filePath = text(entity, "file_path");
            String content = String.format("实体[%s/%s]: %s", name, type, description);
            addSegment(segments, seen, filePath, content);
        }
    }

    private void appendRelationshipSegments(JsonNode relationships, List<TextSegment> segments,
                                            Set<String> seen, int limit) {
        if (relationships == null || !relationships.isArray() || limit <= 0) {
            return;
        }
        for (JsonNode rel : relationships) {
            if (segments.size() >= limit) {
                break;
            }
            String src = text(rel, "src_id");
            String tgt = text(rel, "tgt_id");
            String description = text(rel, "description");
            String keywords = text(rel, "keywords");
            String filePath = text(rel, "file_path");
            if (description == null || description.isBlank()) {
                continue;
            }
            String content = String.format("关系[%s -> %s", src, tgt);
            if (keywords != null && !keywords.isBlank()) {
                content += ", 关键词: " + keywords;
            }
            content += "]: " + description;
            addSegment(segments, seen, filePath, content);
        }
    }

    private void appendChunkSegments(JsonNode chunks, List<TextSegment> segments, Set<String> seen, int limit) {
        if (chunks == null || !chunks.isArray() || limit <= 0) {
            return;
        }
        for (JsonNode chunk : chunks) {
            if (segments.size() >= limit) {
                break;
            }
            String content = text(chunk, "content");
            String filePath = text(chunk, "file_path");
            if (content == null || content.isBlank()) {
                continue;
            }
            addSegment(segments, seen, filePath, content);
        }
    }

    private void addSegment(List<TextSegment> segments, Set<String> seen, String filePath, String content) {
        String dedupeKey = (filePath != null ? filePath : "") + "|" + content;
        if (!seen.add(dedupeKey)) {
            return;
        }
        String fileName = extractDisplayName(filePath);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("file_name", fileName);
        meta.put("file_path", filePath != null ? filePath : fileName);
        Metadata metadata = Metadata.from(meta);
        segments.add(TextSegment.from(content, metadata));
    }

    private static String extractDisplayName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "未知";
        }
        int lastColon = filePath.lastIndexOf(':');
        if (lastColon >= 0 && lastColon < filePath.length() - 1) {
            return filePath.substring(lastColon + 1);
        }
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        return slash >= 0 ? filePath.substring(slash + 1) : filePath;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }
}
