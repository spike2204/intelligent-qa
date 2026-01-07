package com.example.qa.entity;

import javax.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 聊天消息实体
 */
@Data
@Entity
@Table(name = "chat_messages")
public class ChatMessage {
    
    @Id
    @Column(length = 36)
    private String id;
    
    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;
    
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;
    
    @Column(name = "token_count")
    private Integer tokenCount;
    
    @Column(columnDefinition = "TEXT")
    private String citations;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
    
    public enum MessageRole {
        USER, ASSISTANT, SYSTEM
    }
}
