package com.legalassistant.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.legalassistant.client.RAGFlowClient;
import com.legalassistant.config.RAGFlowProperties;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.RAGService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RAGFlowServiceImpl implements RAGService {

    private final RAGFlowClient ragFlowClient;
    private final RAGFlowProperties properties;
    private final DocumentMapper documentMapper;

    @Override
    public void ingestDocument(Long documentId, byte[] fileBytes, String fileName, Long modelConfigId) {
        String displayName = buildDisplayName(documentId, fileName);
        log.info("Ingesting document {} into RAGFlow: {}", documentId, displayName);

        // Replace any previous copy with the same display name
        List<String> existing = ragFlowClient.findDocumentIdsByName(displayName);
        if (!existing.isEmpty()) {
            ragFlowClient.deleteDocuments(existing);
        }

        String ragflowDocId = ragFlowClient.uploadDocument(fileBytes, displayName);
        ragFlowClient.parseDocuments(List.of(ragflowDocId));
        ragFlowClient.pollUntilDone(ragflowDocId);

        int chunkCount = ragFlowClient.countChunks(ragflowDocId);
        if (chunkCount <= 0) {
            chunkCount = 1;
        }

        var doc = documentMapper.selectById(documentId);
        if (doc != null) {
            doc.setChunkCount(chunkCount);
            documentMapper.updateById(doc);
        }
        log.info("Document {} indexed in RAGFlow with {} chunk(s)", documentId, chunkCount);
    }

    @Override
    public List<TextSegment> search(String query, Long modelConfigId) {
        JsonNode response = ragFlowClient.retrieve(query);
        JsonNode data = response.get("data");
        if (data == null || data.isNull()) {
            return List.of();
        }
        return formatChunks(data.get("chunks"));
    }

    @Override
    public void deleteDocumentEmbeddings(Long documentId) {
        String prefix = properties.getFileNamePrefix() + ":" + documentId + ":";
        // List by prefix via keywords-style exact names we know from MySQL is preferred;
        // fall back to scanning documents whose name starts with the prefix.
        List<String> toDelete = new ArrayList<>();
        var doc = documentMapper.selectById(documentId);
        if (doc != null && doc.getFileName() != null) {
            toDelete.addAll(ragFlowClient.findDocumentIdsByName(buildDisplayName(documentId, doc.getFileName())));
        }
        if (toDelete.isEmpty()) {
            // Best-effort: try name prefix match via list-with-keywords if exact name unknown
            toDelete.addAll(ragFlowClient.findDocumentIdsByName(prefix));
        }
        ragFlowClient.deleteDocuments(toDelete);
        log.info("Deleted RAGFlow embeddings for document {} ({} doc id(s))", documentId, toDelete.size());
    }

    public String buildDisplayName(Long documentId, String fileName) {
        return properties.getFileNamePrefix() + ":" + documentId + ":" + fileName;
    }

    public boolean existsByDisplayName(String displayName) {
        return ragFlowClient.existsByName(displayName);
    }

    private List<TextSegment> formatChunks(JsonNode chunks) {
        List<TextSegment> segments = new ArrayList<>();
        if (chunks == null || !chunks.isArray()) {
            return segments;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode chunk : chunks) {
            String content = text(chunk, "content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String docName = text(chunk, "document_keyword");
            if (docName == null || docName.isBlank()) {
                docName = text(chunk, "docnm_kwd");
            }
            String dedupeKey = (docName != null ? docName : "") + "|" + content;
            if (!seen.add(dedupeKey)) {
                continue;
            }
            String fileName = extractDisplayName(docName);
            Metadata metadata = Metadata.from(Map.of(
                    "file_name", fileName,
                    "file_path", docName != null ? docName : fileName
            ));
            segments.add(TextSegment.from(content, metadata));
        }
        return segments;
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
