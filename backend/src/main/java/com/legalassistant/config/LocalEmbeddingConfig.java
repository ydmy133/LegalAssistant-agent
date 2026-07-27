package com.legalassistant.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 本地向量化模型（AllMiniLM-L6-v2 量化版，384 维）。
 * 模型文件随 Maven 依赖下载，进程内运行，不调用 OpenAI Embedding API。
 */
@Configuration
public class LocalEmbeddingConfig {

    @Bean
    @Primary
    @ConditionalOnProperty(name = "legal.embedding.provider", havingValue = "local", matchIfMissing = true)
    public EmbeddingModel localEmbeddingModel() {
        return new AllMiniLmL6V2QuantizedEmbeddingModel();
    }
}
