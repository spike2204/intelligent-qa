package com.example.qa.entity;

import javax.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 文档块实体
 */
@Data
@Entity
@Table(name = "document_chunks")
public class DocumentChunk {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(length = 500)
    private String heading;

    @Column(length = 1000)
    private String hierarchy; // e.g. "1. Basic -> 1.2 Volume"

    @Column(name = "start_page")
    private Integer startPage;

    @Column(name = "end_page")
    private Integer endPage;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "context_prefix", length = 1000)
    private String contextPrefix;

    @Column(name = "vector_id", length = 36)
    private String vectorId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
