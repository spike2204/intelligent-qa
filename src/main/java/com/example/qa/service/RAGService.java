package com.example.qa.service;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.CitationDto;
import com.example.qa.entity.DocumentChunk;
import com.example.qa.repository.DocumentChunkRepository;
import com.example.qa.repository.DocumentRepository;
import com.example.qa.service.embedding.EmbeddingService;
import com.example.qa.service.llm.LLMRouter;
import com.example.qa.service.vector.VectorStore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG检索服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGService {

    private final VectorStore vectorStore;
    private final EmbeddingService embeddingService;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final AppProperties appProperties;

    private final LLMRouter router; // Inject Router

    /**
     * 检索相关文档块
     */
    public RAGResult retrieve(String query, String documentId) {
        int topK = appProperties.getRag().getTopK();
        double threshold = appProperties.getRag().getSimilarityThreshold();

        // 1. Query向量化
        float[] queryEmbedding = embeddingService.embed(query);

        // 2. Router 预测 (Step 1)
        String predictedHierarchy = null;
        if (documentId != null) {
            // 获取该文档的所有 hierarchy (这里简化获取，实际可能需要缓存或从DB distinct查)
            List<String> hierarchies = chunkRepository.findDistinctHierarchyByDocumentId(documentId);
            predictedHierarchy = router.predictHierarchy(query, hierarchies);
            log.info("Router predicted hierarchy: {}", predictedHierarchy);
        }

        // 3. 构建 Filter
        Map<String, Object> filter = new java.util.HashMap<>();
        if (documentId != null) {
            filter.put("documentId", documentId);
        }
        if (predictedHierarchy != null) {
            filter.put("hierarchy", predictedHierarchy);
        }

        List<VectorStore.SearchResult> results;

        // 4. 第一轮检索: 带层级Filter (Step 2)
        if (predictedHierarchy != null) {
            results = vectorStore.search(queryEmbedding, topK, filter);
            log.debug("Hierarchical search results: {}", results.size());

            // Fallback: 如果结果太少或分数太低，回退到全局搜索 (Step 3)
            if (results.isEmpty() || results.get(0).getScore() < threshold) {
                log.info("Hierarchy search failed/low score, falling back to global search");
                filter.remove("hierarchy");
                results = vectorStore.search(queryEmbedding, topK, filter);
            }
        } else {
            // 直接全局搜索
            results = vectorStore.search(queryEmbedding, topK, filter);
        }

        // 5. 打印实际分数用于调试
        if (!results.isEmpty()) {
            log.info("向量检索结果分数: {}",
                    results.stream().map(r -> String.format("%.4f", r.getScore())).collect(Collectors.joining(", ")));
        }

        // 6. 过滤低相似度结果
        List<VectorStore.SearchResult> filtered = results.stream()
                .filter(r -> r.getScore() >= threshold)
                .collect(Collectors.toList());

        log.info("RAG检索: query长度={}, documentId={}, threshold={}, 原始结果={}, 过滤后={}",
                query.length(), documentId, threshold, results.size(), filtered.size());

        // 6. 构建上下文和引用
        return buildRAGResult(filtered);
    }

    private RAGResult buildRAGResult(List<VectorStore.SearchResult> results) {
        StringBuilder context = new StringBuilder();
        List<CitationDto> citations = new ArrayList<>();

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);

            // 构建上下文
            context.append(String.format("[%d] %s\n\n", i + 1, result.getContent()));

            // 构建引用
            String documentName = "未知文档";
            Integer pageNumber = null;

            if (result.getMetadata() != null) {
                Object filenameObj = result.getMetadata().get("filename");
                if (filenameObj != null) {
                    documentName = filenameObj.toString();
                }
                Object pageObj = result.getMetadata().get("startPage");
                if (pageObj instanceof Integer) {
                    pageNumber = (Integer) pageObj;
                }
            }

            citations.add(CitationDto.builder()
                    .chunkId(result.getId())
                    .documentName(documentName)
                    .pageNumber(pageNumber)
                    .excerpt(truncate(result.getContent(), 100))
                    .score(result.getScore())
                    .build());
        }

        return new RAGResult(context.toString(), citations);
    }

    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * RAG检索结果 - 使用普通类替代record
     */
    @Data
    @AllArgsConstructor
    public static class RAGResult {
        private String context;
        private List<CitationDto> citations;
    }
}
