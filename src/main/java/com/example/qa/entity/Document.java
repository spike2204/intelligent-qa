package com.example.qa.entity;

import javax.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档实体
 */
@Data
@Entity
@Table(name = "documents")
public class Document {
    
    @Id
    @Column(length = 36)
    private String id;
    
    @Column(nullable = false)
    private String filename;
    
    @Column(name = "file_type", length = 20)
    private String fileType;
    
    @Column(name = "file_size")
    private Long fileSize;
    
    @Column(name = "file_path", length = 500)
    private String filePath;
    
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private DocumentStatus status = DocumentStatus.UPLOADING;
    
    @Column(name = "chunk_count")
    private Integer chunkCount = 0;
    
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
    
    public enum DocumentStatus {
        UPLOADING, PROCESSING, READY, FAILED
    }
}
