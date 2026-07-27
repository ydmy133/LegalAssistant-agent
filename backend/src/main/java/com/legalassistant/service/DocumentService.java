package com.legalassistant.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    Document upload(MultipartFile file, Long userId);
    Page<Document> list(int page, int size, Long userId);
    void delete(Long id, Long userId);
    void seedPresetDocumentsIfAbsent();
    Long resolveEmbeddingModelConfigId(Long userId);
    /** 向量检索不可用时的关键词回退检索（预置法律文档） */
    String searchPresetDocumentsByKeyword(String query);
}
