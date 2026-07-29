package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.legalassistant.agent.ChatAgent;
import com.legalassistant.agent.LegalTools;
import com.legalassistant.agent.SessionToolGuard;
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
import com.legalassistant.timing.ThoughtEventBus;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    public static final String TIMING_PREFIX = "[TIMING]";
    /** 流式思考/工具事件前缀（前端渲染可展开 Thought） */
    public static final String EVENT_PREFIX = "[EVENT]";

    private final LegalTools legalTools;
    private final SessionToolGuard sessionToolGuard;
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
            sessionToolGuard.beginTurn(sessionId);
            try {
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
                sessionToolGuard.endTurn(sessionId);
            }
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
            sessionToolGuard.beginTurn(sessionId);
            StringBuilder full = new StringBuilder();
            AtomicBoolean firstTokenSeen = new AtomicBoolean(false);
            AtomicBoolean toolSeen = new AtomicBoolean(false);
            List<Map<String, Object>> thoughtSteps = new ArrayList<>();
            ChatTiming.Stage waitFirstLlm = timing.startStage("等待模型首轮", "决策是否调工具/直接回答");
            final ChatTiming.Stage[] streamStage = {null};
            long analyzeStartedAt = System.currentTimeMillis();
            boolean followUp = isFollowUpTurn(content);

            ThoughtEventBus.bind(sessionId, step -> {
                ChatTimingRegistry.bind(timing);
                emitEvent(sink, thoughtSteps, step);
            });

            Map<String, Object> analyze = new LinkedHashMap<>();
            analyze.put("type", "status");
            analyze.put("name", "analyze");
            analyze.put("label", "分析问题");
            analyze.put("status", "running");
            analyze.put("detail", followUp
                    ? "识别为续写请求，判断是否需要重新检索…"
                    : "正在理解问题并决定是否检索…");
            emitEvent(sink, thoughtSteps, analyze);

            if (followUp) {
                Map<String, Object> context = new LinkedHashMap<>();
                context.put("type", "status");
                context.put("name", "context");
                context.put("label", "回顾上文");
                context.put("status", "running");
                context.put("detail", "读取本轮会话上下文与上一轮结论…");
                emitEvent(sink, thoughtSteps, context);
            }

            TokenStream tokenStream = agent.streamChat(sessionId, content);
            tokenStream
                    .onToolExecuted(toolExecution -> {
                        ChatTimingRegistry.bind(timing);
                        ThoughtEventBus.bindSession(sessionId);
                        toolSeen.set(true);
                        String toolName = toolExecution.request() != null
                                ? toolExecution.request().name()
                                : "tool";
                        String arguments = toolExecution.request() != null
                                ? toolExecution.request().arguments()
                                : null;
                        String result = toolExecution.result();
                        log.info("Tool executed: {} (session={})", toolName, sessionId);
                        Long toolDuration = findLatestToolDurationMs(timing, toolName);
                        Map<String, Object> step = new LinkedHashMap<>();
                        step.put("type", "tool");
                        step.put("name", toolName);
                        step.put("label", friendlyToolLabel(toolName));
                        step.put("status", "done");
                        step.put("query", extractToolQuery(arguments));
                        step.put("detail", summarizeToolResult(toolName, result));
                        if (toolDuration != null) {
                            step.put("durationMs", toolDuration);
                        }
                        emitEvent(sink, thoughtSteps, step);
                    })
                    .onPartialResponse(partial -> {
                        ChatTimingRegistry.bind(timing);
                        ThoughtEventBus.bindSession(sessionId);
                        if (partial != null && !partial.isEmpty()) {
                            if (firstTokenSeen.compareAndSet(false, true)) {
                                timing.markFirstToken();
                                if (!waitFirstLlm.isEnded()) {
                                    waitFirstLlm.end(timing.getFirstToolStartMs() == null
                                            ? "开始流式输出"
                                            : "工具后开始流式输出");
                                }
                                streamStage[0] = timing.startStage("流式生成回复", "首字已出");

                                long analyzeMs = Math.max(0, System.currentTimeMillis() - analyzeStartedAt);
                                Map<String, Object> analyzeDone = new LinkedHashMap<>();
                                analyzeDone.put("type", "status");
                                analyzeDone.put("name", "analyze");
                                analyzeDone.put("label", "分析问题");
                                analyzeDone.put("status", "done");
                                analyzeDone.put("durationMs", analyzeMs);
                                analyzeDone.put("detail", toolSeen.get()
                                        ? "已决定调用检索工具"
                                        : (followUp ? "无需重新检索，基于上文展开" : "无需工具，直接作答"));
                                emitEvent(sink, thoughtSteps, analyzeDone);

                                if (followUp && !toolSeen.get()) {
                                    Map<String, Object> skipRetrieve = new LinkedHashMap<>();
                                    skipRetrieve.put("type", "status");
                                    skipRetrieve.put("name", "skip_retrieve");
                                    skipRetrieve.put("label", "跳过检索");
                                    skipRetrieve.put("status", "done");
                                    skipRetrieve.put("detail", "会话内已有相关依据，直接组织详细回答");
                                    emitEvent(sink, thoughtSteps, skipRetrieve);
                                }

                                Map<String, Object> generate = new LinkedHashMap<>();
                                generate.put("type", "status");
                                generate.put("name", "generate");
                                generate.put("label", "生成回答");
                                generate.put("status", "running");
                                generate.put("detail", toolSeen.get()
                                        ? "检索完成，正在组织回复…"
                                        : (followUp ? "正在基于上文生成详细回答…" : "正在组织回复…"));
                                emitEvent(sink, thoughtSteps, generate);
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
                            Object totalObj = timingMap.get("totalMs");
                            long totalMs = totalObj instanceof Number ? ((Number) totalObj).longValue() : 0L;
                            stampThoughtDurations(thoughtSteps, timingMap, totalMs);

                            ChatTiming.Stage persist = timing.startStage("持久化回复", null);
                            try {
                                Map<String, Object> meta = new LinkedHashMap<>();
                                meta.put("timing", timingMap);
                                meta.put("thoughtSteps", thoughtSteps);
                                saveMessage(conv.getId(), "assistant", text,
                                        objectMapper.writeValueAsString(meta));
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
                            ThoughtEventBus.unbind(sessionId);
                            sessionToolGuard.endTurn(sessionId);
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
                            ThoughtEventBus.unbind(sessionId);
                            sessionToolGuard.endTurn(sessionId);
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

    private void emitEvent(FluxSink<String> sink,
                           List<Map<String, Object>> thoughtSteps,
                           Map<String, Object> step) {
        Map<String, Object> copy = new LinkedHashMap<>(step);
        upsertThoughtStep(thoughtSteps, copy);
        try {
            sink.next(EVENT_PREFIX + objectMapper.writeValueAsString(copy));
        } catch (Exception e) {
            log.warn("Failed to emit thought event: {}", e.getMessage());
        }
    }

    /**
     * 同名工具 running→done 合并为一行（类 Cursor/grok 单工具生命周期）。
     */
    static void upsertThoughtStep(List<Map<String, Object>> thoughtSteps, Map<String, Object> step) {
        if (thoughtSteps == null || step == null) {
            return;
        }
        String type = String.valueOf(step.getOrDefault("type", ""));
        String name = step.get("name") != null ? String.valueOf(step.get("name")) : null;
        if (("tool".equals(type) || "status".equals(type)) && name != null && !name.isBlank()) {
            for (int i = thoughtSteps.size() - 1; i >= 0; i--) {
                Map<String, Object> prev = thoughtSteps.get(i);
                if (prev == null) {
                    continue;
                }
                if (type.equals(String.valueOf(prev.getOrDefault("type", "")))
                        && name.equals(String.valueOf(prev.getOrDefault("name", "")))) {
                    prev.putAll(step);
                    step.clear();
                    step.putAll(prev);
                    return;
                }
            }
        }
        thoughtSteps.add(step);
    }

    static boolean isFollowUpTurn(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String c = content.trim();
        return c.contains("详细")
                || c.contains("展开")
                || c.contains("补充")
                || c.contains("继续")
                || c.contains("再写")
                || c.contains("更完整")
                || c.startsWith("帮我生成");
    }

    @SuppressWarnings("unchecked")
    static Long findLatestToolDurationMs(ChatTiming timing, String toolName) {
        if (timing == null || toolName == null) {
            return null;
        }
        Map<String, Object> map = timing.toMap();
        Object stagesObj = map.get("stages");
        if (!(stagesObj instanceof List<?> stages)) {
            return null;
        }
        String suffix = ":" + toolName;
        for (int i = stages.size() - 1; i >= 0; i--) {
            Object raw = stages.get(i);
            if (!(raw instanceof Map<?, ?> stage)) {
                continue;
            }
            Object name = stage.get("name");
            if (name != null && String.valueOf(name).endsWith(suffix)) {
                Object d = stage.get("durationMs");
                if (d instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static void stampThoughtDurations(List<Map<String, Object>> thoughtSteps,
                                      Map<String, Object> timingMap,
                                      long totalMs) {
        if (thoughtSteps == null || thoughtSteps.isEmpty()) {
            return;
        }
        for (Map<String, Object> step : thoughtSteps) {
            if (step == null) {
                continue;
            }
            if ("generate".equals(step.get("name")) || "生成回答".equals(step.get("label"))) {
                step.put("status", "done");
                Object derived = timingMap != null ? timingMap.get("derived") : null;
                if (derived instanceof Map<?, ?> dmap) {
                    Object streamMs = dmap.get("流式输出时长");
                    if (streamMs instanceof Number n) {
                        step.put("durationMs", n.longValue());
                    }
                }
                if (!step.containsKey("durationMs") && totalMs > 0) {
                    step.put("durationMs", totalMs);
                }
                step.putIfAbsent("detail", "回答已生成");
            }
            if ("context".equals(step.get("name")) && !"done".equals(step.get("status"))) {
                step.put("status", "done");
                step.putIfAbsent("detail", "已结合上一轮回答");
            }
        }
    }

    static String friendlyToolLabel(String toolName) {
        if (toolName == null) {
            return "调用工具";
        }
        return switch (toolName) {
            case "searchLegalKnowledge" -> "检索法律知识库";
            case "searchWeb" -> "联网搜索";
            case "searchCases" -> "搜索相关判例";
            case "getCaseDetail" -> "获取案件详情";
            case "getConversationHistory" -> "查阅对话历史";
            default -> "调用工具 " + toolName;
        };
    }

    private String extractToolQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(argumentsJson);
            for (String key : List.of("query", "keyword", "sessionId")) {
                if (node.has(key) && !node.get(key).isNull()) {
                    String value = node.get(key).asText();
                    if (value != null && !value.isBlank() && !"current".equalsIgnoreCase(value)) {
                        return truncateForUi(value, 120);
                    }
                }
            }
            if (node.has("caseId")) {
                return "案件 ID: " + node.get("caseId").asText();
            }
        } catch (Exception ignored) {
            // fall through
        }
        return truncateForUi(argumentsJson, 120);
    }

    static String summarizeToolResult(String toolName, String result) {
        if (result == null || result.isBlank()) {
            return "空结果";
        }
        String normalized = result.replaceAll("\\s+", " ").trim();
        if (normalized.startsWith("本轮已调用过") || normalized.startsWith("本轮 searchLegalKnowledge")) {
            return truncateForUi(normalized, 120);
        }

        int sourceCount = countOccurrences(normalized, "[来源:");
        String firstTitle = null;
        int srcIdx = normalized.indexOf("[来源:");
        if (srcIdx >= 0) {
            int end = normalized.indexOf(']', srcIdx);
            if (end > srcIdx) {
                String header = normalized.substring(srcIdx + 4, end).trim();
                int pipe = header.indexOf('|');
                firstTitle = (pipe > 0 ? header.substring(0, pipe) : header).trim();
            }
        }
        if (firstTitle == null) {
            int caseIdx = normalized.indexOf("案号");
            if (caseIdx >= 0) {
                firstTitle = truncateForUi(normalized.substring(caseIdx), 40);
            }
        }

        StringBuilder sb = new StringBuilder();
        if ("searchWeb".equals(toolName)) {
            if (normalized.contains("未找到") || normalized.contains("未启用")) {
                return truncateForUi(normalized, 120);
            }
            sb.append("hits=").append(Math.max(sourceCount, 1));
        } else if ("searchLegalKnowledge".equals(toolName)) {
            sb.append("hits=").append(sourceCount);
            if (normalized.contains("相关判例")) {
                sb.append(" · 含判例");
            }
        } else if ("searchCases".equals(toolName) || "getCaseDetail".equals(toolName)) {
            sb.append(sourceCount > 0 ? "hits=" + sourceCount : "已返回");
        } else {
            return truncateForUi(normalized, 120);
        }
        if (firstTitle != null && !firstTitle.isBlank()) {
            sb.append(" · ").append(truncateForUi(firstTitle, 48));
        }
        return truncateForUi(sb.toString(), 120);
    }

    private static int countOccurrences(String text, String needle) {
        if (text == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while ((from = text.indexOf(needle, from)) >= 0) {
            count++;
            from += needle.length();
        }
        return count;
    }

    static String truncateForUi(String text, int max) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= max) {
            return normalized;
        }
        return normalized.substring(0, max) + "...";
    }
}
