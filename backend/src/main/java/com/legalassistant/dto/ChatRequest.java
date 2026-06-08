package com.legalassistant.dto;

import lombok.Data;

@Data
public class ChatRequest {
    private String sessionId;
    private String content;
    private Long modelConfigId;
}
