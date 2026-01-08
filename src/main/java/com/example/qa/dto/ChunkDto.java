package com.example.qa.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 文档块DTO - 用于处理流程中传递
 */
@Data
public class ChunkDto {
    private String id;
    private String documentId;
    private String content;
    private int chunkIndex;
    private Integer startPage;
    private Integer endPage;
    private String heading;
    private String hierarchy;
    private String contextPrefix;
    private int tokenCount;
    private Map<String, Object> metadata;
}
