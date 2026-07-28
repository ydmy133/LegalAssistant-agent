package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.Document;
import com.legalassistant.entity.User;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.mapper.UserMapper;
import com.legalassistant.service.DocumentService;
import com.legalassistant.service.RAGService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final UserMapper userMapper;
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
                ragService.ingestDocument(doc.getId(), file.getBytes(), originalName, null);
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
                        .and(w -> w.eq(Document::getUserId, userId)
                                .or()
                                .eq(Document::getIsPreset, 1))
                        .orderByDesc(Document::getCreateTime)
        );
    }

    @Override
    public void delete(Long id, Long userId) {
        Document doc = documentMapper.selectById(id);
        if (doc == null) {
            throw new BusinessException(404, "文档不存在");
        }
        if (Integer.valueOf(1).equals(doc.getIsPreset())) {
            throw new BusinessException("系统预置法律文档不可删除");
        }
        if (!doc.getUserId().equals(userId)) {
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

    @Override
    public void seedPresetDocumentsIfAbsent() {
        Long ownerId = resolveSeedOwnerId();
        if (ownerId == null) {
            log.warn("Skip preset legal documents: register a user first, then restart the app");
            return;
        }

        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:legal-documents/*.{md,txt}");
            int imported = 0;
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                long exists = documentMapper.selectCount(
                        new LambdaQueryWrapper<Document>()
                                .eq(Document::getIsPreset, 1)
                                .eq(Document::getFileName, fileName));
                if (exists > 0) {
                    continue;
                }
                try (InputStream in = resource.getInputStream()) {
                    importPresetDocument(ownerId, fileName, in.readAllBytes());
                    imported++;
                }
            }

            int retried = retryFailedPresetDocuments(ownerId);
            if (imported > 0) {
                log.info("Imported {} preset legal document(s)", imported);
            }
            if (retried > 0) {
                log.info("Re-ingested {} failed preset legal document(s)", retried);
            }
        } catch (Exception e) {
            log.error("Failed to seed preset legal documents", e);
        }
    }

    private int retryFailedPresetDocuments(Long ownerId) {
        List<Document> failed = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getIsPreset, 1)
                        .and(w -> w.eq(Document::getStatus, -1)
                                .or()
                                .eq(Document::getChunkCount, 0)));
        int retried = 0;
        for (Document doc : failed) {
            try {
                byte[] bytes = Files.readAllBytes(Paths.get(doc.getFilePath()));
                if (doc.getChunkCount() != null && doc.getChunkCount() > 0) {
                    ragService.deleteDocumentEmbeddings(doc.getId());
                }
                ragService.ingestDocument(doc.getId(), bytes, doc.getFileName(), null);
                Document updated = documentMapper.selectById(doc.getId());
                if (updated != null) {
                    updated.setStatus(1);
                    documentMapper.updateById(updated);
                }
                retried++;
            } catch (Exception e) {
                log.warn("Preset document {} vectorization failed, file kept in library: {}", doc.getId(), e.getMessage());
                doc.setStatus(1);
                doc.setChunkCount(0);
                documentMapper.updateById(doc);
            }
        }
        return retried;
    }

    @Override
    public Long resolveEmbeddingModelConfigId(Long userId) {
        // RAGFlow 自行管理 Embedding，对话侧不再选择向量模型配置
        return null;
    }

    @Override
    public String searchPresetDocumentsByKeyword(String query) {
        if (query == null || query.isBlank()) {
            return "未在知识库中找到相关内容。";
        }

        List<Document> presets = documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getIsPreset, 1)
                        .eq(Document::getStatus, 1));

        if (presets.isEmpty()) {
            return "未在知识库中找到相关内容。";
        }

        String[] terms = query.replaceAll("[\\s\\p{P}]+", " ").trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        int maxSegments = 5;

        for (Document doc : presets) {
            try {
                String content = Files.readString(Paths.get(doc.getFilePath()));
                String[] blocks = content.split("\n\n+");
                for (String block : blocks) {
                    if (block.isBlank() || block.length() < 20) {
                        continue;
                    }
                    boolean matched = false;
                    for (String term : terms) {
                        if (term.length() >= 2 && block.contains(term)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched && terms.length == 1 && query.length() >= 2 && block.contains(query)) {
                        matched = true;
                    }
                    if (matched) {
                        String snippet = block.length() > 600 ? block.substring(0, 600) + "..." : block;
                        result.append(String.format("[来源: %s] %s\n\n", doc.getFileName(), snippet));
                        if (--maxSegments <= 0) {
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to read preset document {}: {}", doc.getFileName(), e.getMessage());
            }
            if (maxSegments <= 0) {
                break;
            }
        }

        if (result.length() == 0) {
            return "未在知识库中找到相关内容。";
        }
        return result.toString().trim();
    }

    private Long resolveSeedOwnerId() {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().orderByAsc(User::getId).last("LIMIT 1"));
        return user != null ? user.getId() : null;
    }

    private Document importPresetDocument(Long userId, String originalName, byte[] bytes) throws IOException {
        String extension = getFileExtension(originalName);
        if (!extension.matches("pdf|docx|doc|txt|md")) {
            throw new BusinessException("不支持的预置文档类型: " + extension);
        }

        Path uploadPath = Paths.get(uploadDir);
        Files.createDirectories(uploadPath);

        String storedName = "preset-" + UUID.randomUUID() + "." + extension;
        Path filePath = uploadPath.resolve(storedName);
        Files.write(filePath, bytes);

        Document doc = new Document();
        doc.setTitle(originalName);
        doc.setFileName(originalName);
        doc.setFilePath(filePath.toString());
        doc.setFileType(extension);
        doc.setFileSize((long) bytes.length);
        doc.setStatus(0);
        doc.setChunkCount(0);
        doc.setIsPreset(1);
        doc.setUserId(userId);
        documentMapper.insert(doc);

        try {
            ragService.ingestDocument(doc.getId(), bytes, originalName, null);
            doc.setStatus(1);
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.error("Failed to ingest preset document {}: ", doc.getId(), e);
            // 预置法律文档：文件已入库即可在文档管理中展示，向量化失败单独标记
            doc.setStatus(1);
            doc.setChunkCount(0);
            documentMapper.updateById(doc);
        }
        return doc;
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) return "";
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
