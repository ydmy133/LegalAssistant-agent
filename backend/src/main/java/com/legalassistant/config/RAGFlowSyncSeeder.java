package com.legalassistant.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.legalassistant.entity.Document;
import com.legalassistant.mapper.DocumentMapper;
import com.legalassistant.service.RAGService;
import com.legalassistant.service.impl.RAGFlowServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/**
 * 将 MySQL 中已存在的预置文档同步到 RAGFlow（避免仅文档表有记录、未建索引）。
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class RAGFlowSyncSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final DocumentMapper documentMapper;
    private final RAGService ragService;
    private final RAGFlowServiceImpl ragFlowService;
    private final RAGFlowProperties properties;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!StringUtils.hasText(properties.getApiKey()) || !StringUtils.hasText(properties.getDatasetId())) {
            log.warn("RAGFlow sync skipped: api-key or dataset-id not configured");
            return;
        }

        List<Document> presets = documentMapper.selectList(
                new LambdaQueryWrapper<Document>().eq(Document::getIsPreset, 1));

        if (presets.isEmpty()) {
            log.info("RAGFlow sync: no preset documents found");
            return;
        }

        int synced = 0;
        int skipped = 0;
        for (Document doc : presets) {
            if (doc.getFilePath() == null || !Files.exists(Paths.get(doc.getFilePath()))) {
                log.warn("RAGFlow sync: skip missing file for preset document {}", doc.getId());
                continue;
            }

            String displayName = ragFlowService.buildDisplayName(doc.getId(), doc.getFileName());
            try {
                if (ragFlowService.existsByDisplayName(displayName)) {
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
                log.error("RAGFlow sync failed for preset document {} ({}): {}",
                        doc.getId(), doc.getFileName(), e.getMessage());
            }
        }

        log.info("RAGFlow preset sync finished: synced={}, skipped={}", synced, skipped);
    }
}
