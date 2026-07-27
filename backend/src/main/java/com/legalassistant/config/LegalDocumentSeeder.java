package com.legalassistant.config;

import com.legalassistant.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后导入 classpath 下的预置法律文档到文档管理与 RAG 知识库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegalDocumentSeeder implements ApplicationListener<ApplicationReadyEvent> {

    private final DocumentService documentService;

    @Value("${legal.seed-documents.enabled:true}")
    private boolean enabled;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!enabled) {
            log.info("Preset legal document seeding is disabled");
            return;
        }
        documentService.seedPresetDocumentsIfAbsent();
    }
}
