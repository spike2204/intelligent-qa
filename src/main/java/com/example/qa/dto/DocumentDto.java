package com.example.qa.dto;

import com.example.qa.entity.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentDto {
    private String id;
    private String filename;
    private String fileType;
    private Long fileSize;
    private Document.DocumentStatus status;
    private Integer chunkCount;
    private LocalDateTime createdAt;
    private String fullText; // 文档全文 (Markdown)
}
