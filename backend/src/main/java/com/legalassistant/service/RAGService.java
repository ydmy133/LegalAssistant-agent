package com.legalassistant.service;

import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

public interface RAGService {
    void ingestDocument(Long documentId, byte[] fileBytes, String fileName, Long modelConfigId);
    List<TextSegment> search(String query, Long modelConfigId);
    void deleteDocumentEmbeddings(Long documentId);
}
