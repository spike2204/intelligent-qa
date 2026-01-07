package com.example.qa.service.llm;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * LLM客户端接口
 */
public interface LLMClient {
    
    /**
     * 获取模型名称
     */
    String getModelName();
    
    /**
     * 流式对话
     */
    Flux<String> streamChat(ChatRequest request);
    
    /**
     * 同步对话
     */
    String chat(ChatRequest request);
    
    /**
     * 健康检查
     */
    boolean isAvailable();
    
    /**
     * 聊天请求
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class ChatRequest {
        private String systemPrompt;
        private List<Message> messages;
        private int maxTokens;
        private double temperature;
    }
    
    /**
     * 消息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class Message {
        private String role;  // system, user, assistant
        private String content;
    }
}
