package com.legalassistant.service.impl;

import com.legalassistant.entity.UserModelConfig;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.UserModelConfigMapper;
import com.legalassistant.service.ModelService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelServiceImpl implements ModelService {

    private final UserModelConfigMapper modelConfigMapper;

    @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-3-small}")
    private String embeddingModelName;

    @Override
    public ChatModel buildChatModel(Long modelConfigId) {
        UserModelConfig config = modelConfigMapper.selectById(modelConfigId);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }

        var builder = OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .maxTokens(1200)
                .logRequests(true)
                .logResponses(true);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }

        log.info("Built ChatModel: provider={}, model={}", config.getProviderName(), config.getModelName());
        return builder.build();
    }

    @Override
    public OpenAiStreamingChatModel buildStreamingChatModel(Long modelConfigId) {
        UserModelConfig config = modelConfigMapper.selectById(modelConfigId);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }

        var builder = OpenAiStreamingChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .timeout(Duration.ofSeconds(120))
                .maxTokens(1200)
                .logRequests(true)
                .logResponses(true);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }

        log.info("Built StreamingChatModel: provider={}, model={}", config.getProviderName(), config.getModelName());
        return builder.build();
    }

    @Override
    public EmbeddingModel buildEmbeddingModel(Long modelConfigId) {
        UserModelConfig config = modelConfigMapper.selectById(modelConfigId);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在");
        }

        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(config.getApiKey())
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .logRequests(true)
                .logResponses(true);

        if (config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
            builder.baseUrl(config.getBaseUrl());
        }

        log.info("Built EmbeddingModel: provider={}, model={}", config.getProviderName(), embeddingModelName);
        return builder.build();
    }
}
