package com.legalassistant.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.legalassistant.common.UserContext;
import com.legalassistant.dto.ChatRequest;
import com.legalassistant.entity.Conversation;
import com.legalassistant.entity.Message;
import com.legalassistant.service.ChatService;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/send")
    public Result<Map<String, String>> send(@RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        String sessionId = req.getSessionId() != null ? req.getSessionId() : UUID.randomUUID().toString();
        String content = req.getContent();
        if (content == null || content.isBlank()) {
            return Result.fail(400, "消息内容不能为空");
        }
        String response = chatService.sendMessage(sessionId, content, req.getModelConfigId(), userId);
        return Result.ok(Map.of("sessionId", sessionId, "response", response));
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestBody ChatRequest req) {
        Long userId = UserContext.getUserId();
        String sessionId = req.getSessionId() != null ? req.getSessionId() : UUID.randomUUID().toString();
        String content = req.getContent();
        if (content == null || content.isBlank()) {
            return Flux.just("data: 消息内容不能为空\n\n");
        }
        return chatService.sendMessageStream(sessionId, content, req.getModelConfigId(), userId)
                .map(chunk -> "data: " + chunk + "\n\n");
    }

    @GetMapping("/sessions")
    public Result<Page<Conversation>> sessions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        Long userId = UserContext.getUserId();
        return Result.ok(chatService.listSessions(page, size, userId));
    }

    @GetMapping("/{sessionId}/messages")
    public Result<Page<Message>> messages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long userId = UserContext.getUserId();
        return Result.ok(chatService.getMessages(sessionId, page, size, userId));
    }

    @DeleteMapping("/{sessionId}")
    public Result<?> deleteSession(@PathVariable String sessionId) {
        Long userId = UserContext.getUserId();
        chatService.deleteSession(sessionId, userId);
        return Result.ok();
    }

    @PostMapping("/new-session")
    public Result<Map<String, String>> newSession() {
        return Result.ok(Map.of("sessionId", UUID.randomUUID().toString()));
    }
}
