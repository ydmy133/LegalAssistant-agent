package com.legalassistant.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.Document;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentService {
    Document upload(MultipartFile file, Long userId);
    Page<Document> list(int page, int size, Long userId);
    void delete(Long id, Long userId);
}
