package com.legalassistant.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.Document;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.RAGService;
import com.legalassistant.service.impl.LightRAGServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 当 RAG provider=lightrag 时，将 MySQL 中已存在的预置文档同步到 LightRAG（Milvus 索引不会自动迁移）。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "legal.rag.provider", havingValue = "lightrag")
public class LightRAGSyncSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final DocumentMapper documentMapper;
    private final RAGService ragService;
    private final LightRAGServiceImpl lightRAGService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        List<Document> presets = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getIsPreset, 1));

        if (presets.isEmpty()) {
            log.info("LightRAG sync: no preset documents found");
            return;
        }

        int synced = 0;
        int skipped = 0;
        for (Document doc : presets) {
            if (doc.getFilePath() == null || !Files.exists(Paths.get(doc.getFilePath()))) {
                log.warn("LightRAG sync: skip missing file for preset document {}", doc.getId());
                continue;
            }

            String fileSource = lightRAGService.buildFileSource(doc.getId(), doc.getFileName());
            try {
                if (lightRAGService.existsByFileSource(fileSource)) {
                    skipped++;
                    continue;
                }

                byte[] bytes = Files.readAllBytes(Paths.get(doc.getFilePath()));
                ragService.ingestDocument(doc.getId(), bytes, doc.getFileName(), null);
                Document updated = documentMapper.selectById(doc.getId());
                if (updated != null) {
                    updated.setStatus(1);
                    documentMapper.updateById(updated);
                }
                synced++;
            } catch (Exception e) {
                log.error("LightRAG sync failed for preset document {} ({}): {}",
                        doc.getId(), doc.getFileName(), e.getMessage());
            }
        }

        log.info("LightRAG preset sync finished: synced={}, skipped={}", synced, skipped);
    }
}
