package com.example.qa.service.vector;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 向量存储接口
 */
public interface VectorStore {
    
    /**
     * 插入向量文档
     */
    void insert(List<VectorDocument> documents);
    
    /**
     * 搜索相似向量
     */
    List<SearchResult> search(float[] queryVector, int topK, Map<String, Object> filter);
    
    /**
     * 按文档ID删除
     */
    void deleteByDocumentId(String documentId);
    
    /**
     * 向量文档
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class VectorDocument {
        private String id;
        private String documentId;
        private String content;
        private float[] embedding;
        private Map<String, Object> metadata;
    }
    
    /**
     * 搜索结果
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class SearchResult {
        private String id;
        private String documentId;
        private String content;
        private double score;
        private Map<String, Object> metadata;
    }
}
