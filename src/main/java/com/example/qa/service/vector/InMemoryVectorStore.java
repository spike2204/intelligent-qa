package com.example.qa.service.vector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存向量存储 - 用于开发测试
 */
@Slf4j
@Component
public class InMemoryVectorStore implements VectorStore {

    private final Map<String, VectorDocument> storage = new ConcurrentHashMap<>();

    @Override
    public void insert(List<VectorDocument> documents) {
        for (VectorDocument doc : documents) {
            storage.put(doc.getId(), doc);
        }
        log.info("向量存储: 插入{}条文档, 总数={}", documents.size(), storage.size());
    }

    @Override
    public List<SearchResult> search(float[] queryVector, int topK, Map<String, Object> filter) {
        String documentIdFilter = filter != null ? (String) filter.get("documentId") : null;

        String hierarchyFilter = filter != null ? (String) filter.get("hierarchy") : null;

        // 调试: 打印query向量维度
        log.info("搜索向量维度: query={}, 存储文档数={}",
                queryVector != null ? queryVector.length : 0, storage.size());

        // 调试: 检查存储的文档向量维度
        if (!storage.isEmpty()) {
            VectorDocument firstDoc = storage.values().iterator().next();
            log.info("存储向量维度示例: {}", firstDoc.getEmbedding() != null ? firstDoc.getEmbedding().length : 0);
        }

        List<SearchResult> results = storage.values().stream()
                .filter(doc -> {
                    // Document ID Filter
                    if (documentIdFilter != null && !documentIdFilter.equals(doc.getDocumentId())) {
                        return false;
                    }
                    // Hierarchy Filter (Prefix Match)
                    if (hierarchyFilter != null) {
                        String docHierarchy = (String) doc.getMetadata().get("hierarchy");
                        return docHierarchy != null && docHierarchy.startsWith(hierarchyFilter);
                    }
                    return true;
                })
                .map(doc -> {
                    double score = cosineSimilarity(queryVector, doc.getEmbedding());
                    return SearchResult.builder()
                            .id(doc.getId())
                            .documentId(doc.getDocumentId())
                            .content(doc.getContent())
                            .score(score)
                            .metadata(doc.getMetadata())
                            .build();
                })
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());

        log.debug("向量搜索: topK={}, 返回{}条结果", topK, results.size());
        return results;
    }

    @Override
    public void deleteByDocumentId(String documentId) {
        List<String> toDelete = storage.entrySet().stream()
                .filter(e -> documentId.equals(e.getValue().getDocumentId()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        toDelete.forEach(storage::remove);
        log.info("向量存储: 删除文档{}的{}条向量", documentId, toDelete.size());
    }

    /**
     * 计算余弦相似度
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }

        if (normA == 0 || normB == 0) {
            return 0.0;
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
