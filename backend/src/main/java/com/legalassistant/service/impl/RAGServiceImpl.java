package com.legalassistant.service.impl;

import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.ModelService;
import com.legalassistant.service.RAGService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legal.rag.provider", havingValue = "milvus", matchIfMissing = true)
public class RAGServiceImpl implements RAGService {

    private final EmbeddingModel defaultEmbeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final DocumentMapper documentMapper;
    private final ModelService modelService;

    @Value("${legal.embedding.provider:local}")
    private String embeddingProvider;

    private static final int MAX_SEGMENT_SIZE = 500;
    private static final int MAX_OVERLAP_SIZE = 50;

    @Override
    public void ingestDocument(Long documentId, byte[] fileBytes, String fileName, Long modelConfigId) {
        log.info("Ingesting document {}: {}", documentId, fileName);

        EmbeddingModel embeddingModel = resolveEmbeddingModel(modelConfigId);

        DocumentParser parser = new ApacheTikaDocumentParser();
        Document document = parser.parse(new ByteArrayInputStream(fileBytes));

        var splitter = DocumentSplitters.recursive(
                MAX_SEGMENT_SIZE,
                MAX_OVERLAP_SIZE
        );

        List<TextSegment> segments = splitter.split(document);
        log.info("Document {} split into {} chunks", documentId, segments.size());

        for (TextSegment segment : segments) {
            segment.metadata().put("document_id", documentId.toString());
            segment.metadata().put("file_name", fileName);
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        log.info("Document {} embedded and stored in Milvus", documentId);

        var doc = documentMapper.selectById(documentId);
        if (doc != null) {
            doc.setChunkCount(segments.size());
            documentMapper.updateById(doc);
        }
    }

    @Override
    public List<TextSegment> search(String query, Long modelConfigId) {
        EmbeddingModel embeddingModel = resolveEmbeddingModel(modelConfigId);

        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(5)
                        .minScore(0.5)
                        .build()
        );
        return result.matches().stream()
                .map(m -> m.embedded())
                .toList();
    }

    @Override
    public void deleteDocumentEmbeddings(Long documentId) {
        // Milvus metadata-filtered deletion - pass filter as Collection
        String filterExpr = "document_id == \"" + documentId + "\"";
        embeddingStore.removeAll(java.util.List.of(filterExpr));
        log.info("Deleted embeddings for document {}", documentId);
    }

    private EmbeddingModel resolveEmbeddingModel(Long modelConfigId) {
        if ("local".equalsIgnoreCase(embeddingProvider)) {
            return defaultEmbeddingModel;
        }
        if (modelConfigId != null) {
            return modelService.buildEmbeddingModel(modelConfigId);
        }
        return defaultEmbeddingModel;
    }
}
