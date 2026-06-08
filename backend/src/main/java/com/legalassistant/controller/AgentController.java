package com.legalassistant.controller;

import com.legalassistant.agent.LegalAssistantAgent;
import com.legalassistant.dto.ChatRequest;
import com.legalassistant.dto.ChatResponse;
import com.legalassistant.vo.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final LegalAssistantAgent agent;

    @PostMapping("/chat")
    public Result<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId() != null
                ? request.getSessionId()
                : UUID.randomUUID().toString();
        String response = agent.chat(sessionId, request.getContent());
        return Result.ok(new ChatResponse(sessionId, response));
    }
}
