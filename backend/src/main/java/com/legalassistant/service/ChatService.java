package com.legalassistant.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.entity.Conversation;
import com.legalassistant.entity.Message;
import reactor.core.publisher.Flux;

public interface ChatService {
    String sendMessage(String sessionId, String content, Long modelConfigId, Long userId);
    Flux<String> sendMessageStream(String sessionId, String content, Long modelConfigId, Long userId);
    Page<Conversation> listSessions(int page, int size, Long userId);
    Page<Message> getMessages(String sessionId, int page, int size, Long userId);
    void deleteSession(String sessionId, Long userId);
}
