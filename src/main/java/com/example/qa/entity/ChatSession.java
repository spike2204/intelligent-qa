package com.example.qa.entity;

import javax.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 聊天会话实体
 */
@Data
@Entity
@Table(name = "chat_sessions")
public class ChatSession {
    
    @Id
    @Column(length = 36)
    private String id;
    
    @Column(name = "document_id", length = 36)
    private String documentId;
    
    @Column(columnDefinition = "TEXT")
    private String summary;
    
    @Column(name = "message_count")
    private Integer messageCount = 0;
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
