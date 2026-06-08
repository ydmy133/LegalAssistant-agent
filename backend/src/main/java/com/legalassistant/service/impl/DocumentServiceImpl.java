package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.Document;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final RAGService ragService;

    @Value("${file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public Document upload(MultipartFile file, Long userId) {
        if (file.isEmpty()) {
            throw new BusinessException("文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        String extension = getFileExtension(originalName);
        if (!extension.matches("pdf|docx|doc|txt|md")) {
            throw new BusinessException("不支持的文件类型: " + extension);
        }

        try {
            Path uploadPath = Paths.get(uploadDir);
            Files.createDirectories(uploadPath);

            String storedName = UUID.randomUUID() + "." + extension;
            Path filePath = uploadPath.resolve(storedName);
            file.transferTo(filePath.toFile());

            Document doc = new Document();
            doc.setTitle(originalName);
            doc.setFileName(originalName);
            doc.setFilePath(filePath.toString());
            doc.setFileType(extension);
            doc.setFileSize(file.getSize());
            doc.setStatus(0);
            doc.setChunkCount(0);
            doc.setUserId(userId);
            documentMapper.insert(doc);

            try {
                ragService.ingestDocument(doc.getId(), file.getBytes(), originalName);
                doc.setStatus(1);
                documentMapper.updateById(doc);
            } catch (Exception e) {
                log.error("Failed to ingest document {}: ", doc.getId(), e);
                doc.setStatus(-1);
                documentMapper.updateById(doc);
            }

            return doc;
        } catch (IOException e) {
            throw new BusinessException("文件上传失败: " + e.getMessage());
        }
    }

    @Override
    public Page<Document> list(int page, int size, Long userId) {
        return documentMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getUserId, userId)
                        .orderByDesc(Document::getCreateTime)
        );
    }

    @Override
    public void delete(Long id, Long userId) {
        Document doc = documentMapper.selectById(id);
        if (doc == null || !doc.getUserId().equals(userId)) {
            throw new BusinessException(404, "文档不存在");
        }
        ragService.deleteDocumentEmbeddings(id);
        try {
            Files.deleteIfExists(Paths.get(doc.getFilePath()));
        } catch (IOException e) {
            log.warn("Failed to delete file: {}", doc.getFilePath());
        }
        documentMapper.deleteById(id);
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
