package com.legalassistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalassistant.client.LightRAGClient;
import com.legalassistant.config.LightRAGProperties;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.RAGService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legal.rag.provider", havingValue = "lightrag")
public class LightRAGServiceImpl implements RAGService {

    private final LightRAGClient lightRAGClient;
    private final LightRAGProperties properties;
    private final DocumentMapper documentMapper;

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
        log.info("Document {} indexed in LightRAG with {} chunk(s)", documentId, chunkCount);
    }

    @Override
    public List<TextSegment> search(String query, Long modelConfigId) {
        JsonNode response = lightRAGClient.queryData(query, properties.getQueryMode());
        JsonNode data = response.get("data");
        if (data == null || data.isNull()) {
            return List.of();
        }
        return formatQueryData(data);
    }

    @Override
    public void deleteDocumentEmbeddings(Long documentId) {
        String prefix = properties.getFileSourcePrefix() + ":" + documentId + ":";
        List<String> docIds = lightRAGClient.findDocIdsByFileSource(prefix);
        lightRAGClient.deleteByDocIds(docIds);
        log.info("Deleted LightRAG embeddings for document {} ({} doc id(s))", documentId, docIds.size());
    }

    public String buildFileSource(Long documentId, String fileName) {
        return properties.getFileSourcePrefix() + ":" + documentId + ":" + fileName;
    }

    public boolean existsByFileSource(String fileSource) {
        return lightRAGClient.existsByFileSource(fileSource);
    }

    private List<TextSegment> formatQueryData(JsonNode data) {
        List<TextSegment> segments = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        appendEntitySegments(data.get("entities"), segments, seen);
        appendRelationshipSegments(data.get("relationships"), segments, seen);
        appendChunkSegments(data.get("chunks"), segments, seen);
        return segments;
    }

    private void appendEntitySegments(JsonNode entities, List<TextSegment> segments, Set<String> seen) {
        if (entities == null || !entities.isArray()) {
            return;
        }
        for (JsonNode entity : entities) {
            String name = text(entity, "entity_name");
            String type = text(entity, "entity_type");
            String description = text(entity, "description");
            String filePath = text(entity, "file_path");
            if (description == null || description.isBlank()) {
                continue;
            }
            String content = String.format("实体[%s/%s]: %s", name, type, description);
            addSegment(segments, seen, filePath, content);
        }
    }

    private void appendRelationshipSegments(JsonNode relationships, List<TextSegment> segments, Set<String> seen) {
        if (relationships == null || !relationships.isArray()) {
            return;
        }
        for (JsonNode rel : relationships) {
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

    private void appendChunkSegments(JsonNode chunks, List<TextSegment> segments, Set<String> seen) {
        if (chunks == null || !chunks.isArray()) {
            return;
        }
        for (JsonNode chunk : chunks) {
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
        Metadata metadata = Metadata.from(Map.of(
                "file_name", fileName,
                "file_path", filePath != null ? filePath : fileName
        ));
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
