package com.legalassistant.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final LegalTools legalTools;
    private final ModelService modelService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final UserModelConfigMapper modelConfigMapper;

    private final Map<String, MessageWindowChatMemory> memoryCache = new ConcurrentHashMap<>();

    @Override
    public String sendMessage(String sessionId, String content, Long modelConfigId, Long userId) {
        Conversation conv = getOrCreateConversation(sessionId, userId);
        saveMessage(conv.getId(), "user", content);

        Long configId = resolveModelConfigId(modelConfigId, userId);
        ChatModel chatModel = modelService.buildChatModel(configId);

        MessageWindowChatMemory memory = memoryCache.computeIfAbsent(sessionId,
                k -> MessageWindowChatMemory.withMaxMessages(20));

        ChatAgent agent = AiServices.builder(ChatAgent.class)
                .chatModel(chatModel)
                .tools(legalTools)
                .chatMemory(memory)
                .build();

        String response = agent.chat(sessionId, content);
        saveMessage(conv.getId(), "assistant", response);

        if ("新对话".equals(conv.getTitle())) {
            conv.setTitle(content.length() > 50 ? content.substring(0, 50) + "..." : content);
            conversationMapper.updateById(conv);
        }

        return response;
    }

    @Override
    public Flux<String> sendMessageStream(String sessionId, String content, Long modelConfigId, Long userId) {
        // Fall back to blocking call for now; streaming model requires separate wiring
        String response = sendMessage(sessionId, content, modelConfigId, userId);
        return Flux.just(response);
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
        Message msg = new Message();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        messageMapper.insert(msg);
    }
}
