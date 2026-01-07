package com.example.qa.service.embedding;

import java.util.List;

/**
 * Embedding服务接口
 */
public interface EmbeddingService {
    
    /**
     * 获取单个文本的embedding向量
     */
    float[] embed(String text);
    
    /**
     * 批量获取embedding向量
     */
    List<float[]> embedBatch(List<String> texts);
    
    /**
     * 获取向量维度
     */
    int getDimension();
}
