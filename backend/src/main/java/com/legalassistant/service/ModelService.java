package com.legalassistant.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

public interface ModelService {
    ChatModel buildChatModel(Long modelConfigId);
    OpenAiStreamingChatModel buildStreamingChatModel(Long modelConfigId);
}
