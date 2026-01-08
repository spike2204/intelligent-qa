package com.example.qa.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用来源DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDto {
    private String chunkId;
    private String documentId; // 新增文档ID字段
    private String documentName;
    private Integer pageNumber;
    private String excerpt; // 原始内容截取
    private String summary; // LLM 生成的摘要
    private Double score;
}
