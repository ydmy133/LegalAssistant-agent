package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalassistant.agent.ChatAgent;
import com.legalassistant.agent.LegalTools;
import com.legalassistant.entity.Conversation;
import com.legalassistant.entity.Message;
import com.legalassistant.entity.UserModelConfig;
import com.legalassistant.exception.BusinessException;
import com.legalassistant.mapper.ConversationMapper;
import com.legalassistant.mapper.MessageMapper;
import com.legalassistant.mapper.UserModelConfigMapper;
import com.legalassistant.service.ChatService;
import com.legalassistant.service.ModelService;
import com.legalassistant.timing.ChatTiming;
import com.legalassistant.timing.ChatTimingRegistry;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    public static final String TIMING_PREFIX = "[TIMING]";

    private final LegalTools legalTools;
    private final ModelService modelService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserModelConfigMapper modelConfigMapper;
    private final ObjectMapper objectMapper;

    private final Map<String, MessageWindowChatMemory> memoryCache = new ConcurrentHashMap<>();

    @Override
    public String sendMessage(String sessionId, String content, Long modelConfigId, Long userId) {
        ChatTiming timing = ChatTimingRegistry.begin(sessionId);
        ChatTiming.Stage prepare = timing.startStage("准备请求", "sync");
        try {
            Conversation conv = getOrCreateConversation(sessionId, userId);
            saveMessage(conv.getId(), "user", content);

            Long configId = resolveModelConfigId(modelConfigId, userId);
            UserModelConfig config = modelConfigMapper.selectById(configId);
            if (config != null) {
                timing.setModelName(config.getProviderName() + "/" + config.getModelName());
            }
            ChatModel chatModel = modelService.buildChatModel(configId);
            prepare.end("modelReady");

            ChatTiming.Stage agentStage = timing.startStage("Agent调用", "非流式");
            ChatAgent agent = AiServices.builder(ChatAgent.class)
                    .chatModel(chatModel)
                    .tools(legalTools)
                    .chatMemoryProvider(memoryId -> memoryCache.computeIfAbsent(
                            String.valueOf(memoryId),
                            k -> MessageWindowChatMemory.withMaxMessages(20)))
                    .build();

            String response = agent.chat(sessionId, content);
            agentStage.end("chars=" + (response != null ? response.length() : 0));

            timing.markStreamComplete();
            Map<String, Object> timingMap = timing.toMap();
            ChatTiming.Stage persist = timing.startStage("持久化回复", null);
            try {
                saveMessage(conv.getId(), "assistant", response,
                        objectMapper.writeValueAsString(Map.of("timing", timingMap)));
            } catch (Exception metaEx) {
                saveMessage(conv.getId(), "assistant", response, null);
            }
            if ("新对话".equals(conv.getTitle())) {
                conv.setTitle(content.length() > 50 ? content.substring(0, 50) + "..." : content);
                conversationMapper.updateById(conv);
            }
            persist.end();
            log.info("Chat timing [{}]: {}", sessionId, timingMap.get("summary"));
            return response;
        } finally {
            ChatTimingRegistry.end(sessionId);
        }
    }

    @Override
    public Flux<String> sendMessageStream(String sessionId, String content, Long modelConfigId, Long userId) {
        ChatTiming timing = ChatTimingRegistry.begin(sessionId);
        ChatTiming.Stage prepare = timing.startStage("准备请求", "保存消息/构建模型");

        Conversation conv;
        StreamingChatModel streamingChatModel;
        try {
            conv = getOrCreateConversation(sessionId, userId);
            saveMessage(conv.getId(), "user", content);

            Long configId = resolveModelConfigId(modelConfigId, userId);
            UserModelConfig config = modelConfigMapper.selectById(configId);
            if (config != null) {
                timing.setModelName(config.getProviderName() + "/" + config.getModelName());
            }
            streamingChatModel = modelService.buildStreamingChatModel(configId);
            prepare.end("modelReady");
        } catch (RuntimeException e) {
            prepare.end("error=" + e.getMessage());
            ChatTimingRegistry.end(sessionId);
            throw e;
        }

        ChatAgent agent = AiServices.builder(ChatAgent.class)
                .streamingChatModel(streamingChatModel)
                .tools(legalTools)
                .chatMemoryProvider(memoryId -> memoryCache.computeIfAbsent(
                        String.valueOf(memoryId),
                        k -> MessageWindowChatMemory.withMaxMessages(20)))
                .build();

        return Flux.create(sink -> {
            ChatTimingRegistry.bind(timing);
            StringBuilder full = new StringBuilder();
            AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
            ChatTiming.Stage waitFirstLlm = timing.startStage("等待模型首轮", "决策是否调工具/直接回答");
            final ChatTiming.Stage[] streamStage = {null};

            TokenStream tokenStream = agent.streamChat(sessionId, content);
            tokenStream
                    .onToolExecuted(toolExecution -> {
                        ChatTimingRegistry.bind(timing);
                        log.info("Tool executed: {} (session={})", toolExecution.request().name(), sessionId);
                    })
                    .onPartialResponse(partial -> {
                        ChatTimingRegistry.bind(timing);
                        if (partial != null && !partial.isEmpty()) {
                            if (firstTokenSeen.compareAndSet(false, true)) {
                                timing.markFirstToken();
                                if (!waitFirstLlm.isEnded()) {
                                    waitFirstLlm.end(timing.getFirstToolStartMs() == null
                                            ? "开始流式输出"
                                            : "工具后开始流式输出");
                                }
                                streamStage[0] = timing.startStage("流式生成回复", "首字已出");
                            }
                            full.append(partial);
                            sink.next(partial);
                        }
                    })
                    .onCompleteResponse(response -> {
                        ChatTimingRegistry.bind(timing);
                        try {
                            timing.markStreamComplete();
                            if (!waitFirstLlm.isEnded()) {
                                waitFirstLlm.end("完成时仍未产生首字");
                            }
                            if (streamStage[0] != null && !streamStage[0].isEnded()) {
                                streamStage[0].end("chars=" + full.length());
                            }

                            String text = response != null && response.aiMessage() != null
                                    ? response.aiMessage().text()
                                    : null;
                            if (text == null || text.isBlank()) {
                                text = full.toString();
                            }
                            if (response != null && response.tokenUsage() != null) {
                                TokenUsage usage = response.tokenUsage();
                                timing.setTokenUsage(usage.inputTokenCount(), usage.outputTokenCount());
                            }

                            Map<String, Object> timingMap = timing.toMap();
                            ChatTiming.Stage persist = timing.startStage("持久化回复", null);
                            try {
                                saveMessage(conv.getId(), "assistant", text,
                                        objectMapper.writeValueAsString(Map.of("timing", timingMap)));
                            } catch (Exception metaEx) {
                                saveMessage(conv.getId(), "assistant", text, null);
                            }
                            if ("新对话".equals(conv.getTitle())) {
                                conv.setTitle(content.length() > 50 ? content.substring(0, 50) + "..." : content);
                                conversationMapper.updateById(conv);
                            }
                            persist.end();

                            log.info("Chat timing [{}]: {}", sessionId, timingMap.get("summary"));
                            sink.next(TIMING_PREFIX + objectMapper.writeValueAsString(timingMap));
                            sink.next("[DONE]");
                            sink.complete();
                        } catch (Exception e) {
                            log.error("Failed to complete streaming chat, sessionId={}", sessionId, e);
                            sink.error(e);
                        } finally {
                            ChatTimingRegistry.end(sessionId);
                        }
                    })
                    .onError(error -> {
                        ChatTimingRegistry.bind(timing);
                        try {
                            timing.markStreamComplete();
                            if (!waitFirstLlm.isEnded()) {
                                waitFirstLlm.end("error");
                            }
                            if (streamStage[0] != null && !streamStage[0].isEnded()) {
                                streamStage[0].end("error");
                            }
                            log.error("Streaming chat failed, sessionId={}, timing={}",
                                    sessionId, timing.toMap().get("summary"), error);
                            if (full.length() > 0) {
                                saveMessage(conv.getId(), "assistant", full.toString(), timingMetadataJson(timing));
                            }
                            try {
                                sink.next(TIMING_PREFIX + objectMapper.writeValueAsString(timing.toMap()));
                            } catch (Exception ignored) {
                                // ignore timing serialization failure on error path
                            }
                            sink.error(error);
                        } finally {
                            ChatTimingRegistry.end(sessionId);
                        }
                    })
                    .start();
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    @Override
    public Page<Conversation> listSessions(int page, int size, Long userId) {
        return conversationMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getUserId, userId)
                        .orderByDesc(Conversation::getUpdateTime)
        );
    }

    @Override
    public Page<Message> getMessages(String sessionId, int page, int size, Long userId) {
        Conversation conv = getConversation(sessionId, userId);
        if (conv == null) {
            return new Page<>(page, size);
        }
        return messageMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<Message>()
                        .eq(Message::getConversationId, conv.getId())
                        .orderByAsc(Message::getCreateTime)
        );
    }

    @Override
    public void deleteSession(String sessionId, Long userId) {
        Conversation conv = getConversation(sessionId, userId);
        if (conv != null) {
            messageMapper.delete(new LambdaQueryWrapper<Message>()
                    .eq(Message::getConversationId, conv.getId()));
            conversationMapper.deleteById(conv.getId());
            memoryCache.remove(sessionId);
        }
    }

    private Long resolveModelConfigId(Long modelConfigId, Long userId) {
        if (modelConfigId != null) {
            return modelConfigId;
        }
        UserModelConfig defaultConfig = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<UserModelConfig>()
                        .eq(UserModelConfig::getUserId, userId)
                        .eq(UserModelConfig::getIsDefault, 1)
        );
        if (defaultConfig != null) {
            return defaultConfig.getId();
        }
        var configs = modelConfigMapper.selectList(
                new LambdaQueryWrapper<UserModelConfig>()
                        .eq(UserModelConfig::getUserId, userId)
                        .last("LIMIT 1")
        );
        if (configs.isEmpty()) {
            throw new BusinessException(400, "请先在设置中配置模型API Key");
        }
        return configs.get(0).getId();
    }

    private Conversation getOrCreateConversation(String sessionId, Long userId) {
        Conversation conv = getConversation(sessionId, userId);
        if (conv == null) {
            conv = new Conversation();
            conv.setSessionId(sessionId);
            conv.setTitle("新对话");
            conv.setUserId(userId);
            conversationMapper.insert(conv);
        }
        return conv;
    }

    private Conversation getConversation(String sessionId, Long userId) {
        return conversationMapper.selectOne(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId)
                        .eq(Conversation::getUserId, userId)
        );
    }

    private void saveMessage(Long conversationId, String role, String content) {
        saveMessage(conversationId, role, content, null);
    }

    private void saveMessage(Long conversationId, String role, String content, String metadataJson) {
        Message msg = new Message();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setMetadataJson(metadataJson);
        messageMapper.insert(msg);
    }

    private String timingMetadataJson(ChatTiming timing) {
        try {
            return objectMapper.writeValueAsString(Map.of("timing", timing.toMap()));
        } catch (Exception e) {
            log.warn("Failed to serialize timing metadata: {}", e.getMessage());
            return null;
        }
    }
}
