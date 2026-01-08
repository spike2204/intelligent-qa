package com.example.qa.service;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.CitationDto;
import com.example.qa.repository.DocumentChunkRepository;
import com.example.qa.repository.DocumentRepository;
import com.example.qa.service.embedding.EmbeddingService;
import com.example.qa.service.llm.LLMClient;
import com.example.qa.service.llm.LLMRouter;
import com.example.qa.service.retrieval.BM25Service;
import com.example.qa.service.vector.VectorStore;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG检索服务 - 支持混合检索（向量 + BM25）
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
    private final LLMRouter router;
    private final BM25Service bm25Service;

    /**
     * 多文档检索 - 从多个文档中检索相关内容
     * 使用轮询方式确保每个文档都有结果
     */
    public RAGResult retrieveMulti(String query, List<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            log.info("多文档检索: 无文档ID，返回空结果");
            return new RAGResult("", new ArrayList<>());
        }

        if (documentIds.size() == 1) {
            // 单文档直接使用原方法
            return retrieve(query, documentIds.get(0));
        }

        log.info("=== 多文档RAG检索开始 (Global Search) ===");
        log.info("查询: {}, 文档列表: {}", query, documentIds);

        int topK = appProperties.getRag().getTopK();
        float[] queryEmbedding = embeddingService.embed(query);

        // 1. 全局向量检索 (使用 documentId 列表过滤)
        Map<String, Object> filter = new HashMap<>();
        filter.put("documentId", documentIds); // 传递列表给 VectorStore
        List<VectorStore.SearchResult> vectorResults = vectorStore.search(queryEmbedding, topK, filter);
        log.info("全局向量检索结果数: {}", vectorResults.size());

        // 2. 全局BM25检索
        List<BM25Service.BM25Result> bm25Results = bm25Service.search(query, documentIds, topK);
        log.info("全局BM25检索结果数: {}", bm25Results.size());

        // 3. RRF融合
        List<VectorStore.SearchResult> fusedResults = fuseResults(bm25Results, vectorResults, topK);
        log.info("多文档融合后结果数: {}", fusedResults.size());

        return buildRAGResult(fusedResults);
    }

    /**
     * 检索相关文档块
     */
    public RAGResult retrieve(String query, String documentId) {
        log.info("=== RAG检索开始 ===");
        log.info("原始查询: {}", query);
        log.info("文档ID: {}", documentId);

        // 【Plan B】小文档直接模式 - 跳过检索，直接使用完整文档
        if (documentId != null) {
            RAGResult directResult = trySmallDocumentMode(documentId, query);
            if (directResult != null) {
                return directResult;
            }
        }

        int topK = appProperties.getRag().getTopK();
        double threshold = appProperties.getRag().getSimilarityThreshold();

        // 【优化】查询扩展 - 将短查询扩展为更丰富的语义
        String expandedQuery = expandQuery(query);
        if (!expandedQuery.equals(query)) {
            log.info("查询扩展: {} -> {}", query, expandedQuery);
        }

        // 1. Query向量化 (使用扩展后的查询)
        float[] queryEmbedding = embeddingService.embed(expandedQuery);

        // 2. Router 预测
        String predictedHierarchy = null;
        if (documentId != null) {
            List<String> hierarchies = chunkRepository.findDistinctHierarchyByDocumentId(documentId);
            log.debug("可用层级: {}", hierarchies);
            predictedHierarchy = router.predictHierarchy(query, hierarchies);
            log.info("Router预测层级: {}", predictedHierarchy);
        }

        // 3. 构建 Filter
        Map<String, Object> filter = new HashMap<>();
        if (documentId != null) {
            filter.put("documentId", documentId);
        }
        if (predictedHierarchy != null) {
            filter.put("hierarchy", predictedHierarchy);
        }

        // ========== 【核心优化】混合检索 ==========

        // 4a. BM25关键词检索 (使用原始查询保留精确关键词)
        List<BM25Service.BM25Result> bm25Results = Collections.emptyList();
        if (documentId != null) {
            bm25Results = bm25Service.search(query, documentId, topK);
            log.info("BM25检索结果数: {}", bm25Results.size());
            if (!bm25Results.isEmpty()) {
                log.info("BM25最高分: {:.4f}", bm25Results.get(0).getScore());
            }
        }

        // 4b. 向量语义检索
        List<VectorStore.SearchResult> vectorResults;
        if (predictedHierarchy != null) {
            vectorResults = vectorStore.search(queryEmbedding, topK, filter);
            log.info("层级向量检索结果数: {}", vectorResults.size());

            // 增强Fallback逻辑
            boolean needFallback = vectorResults.isEmpty()
                    || vectorResults.size() < Math.max(2, topK / 2)
                    || vectorResults.get(0).getScore() < threshold * 1.2;

            if (needFallback) {
                log.info("层级检索效果不佳, 回退到全局搜索");
                filter.remove("hierarchy");
                vectorResults = vectorStore.search(queryEmbedding, topK, filter);
            }
        } else {
            vectorResults = vectorStore.search(queryEmbedding, topK, filter);
        }
        log.info("向量检索结果数: {}", vectorResults.size());

        // 5. 【核心】RRF融合 (Reciprocal Rank Fusion)
        List<VectorStore.SearchResult> fusedResults = fuseResults(bm25Results, vectorResults, topK);
        log.info("融合后结果数: {}", fusedResults.size());

        // 6. 打印融合后的结果
        if (!fusedResults.isEmpty()) {
            log.info("融合检索结果分数: {}",
                    fusedResults.stream().map(r -> String.format("%.4f", r.getScore()))
                            .collect(Collectors.joining(", ")));
            for (int i = 0; i < Math.min(3, fusedResults.size()); i++) {
                VectorStore.SearchResult r = fusedResults.get(i);
                String preview = r.getContent().length() > 80
                        ? r.getContent().substring(0, 80) + "..."
                        : r.getContent();
                log.info("  结果{}: 预览: {}", i + 1, preview);
            }
        } else {
            log.warn("检索无结果!");
        }

        // 7. 【修复】RRF分数范围是0.01-0.03，不能用原始阈值过滤
        // 直接使用融合排序结果，不做分数过滤（RRF已经做了排序）
        List<VectorStore.SearchResult> filtered = fusedResults;

        // 如果完全没有结果，记录警告
        if (filtered.isEmpty()) {
            log.warn("RAG检索无结果: 请检查文档是否已正确索引");
        }

        log.info("RAG检索完成: 融合结果={}, 最终使用={}",
                fusedResults.size(), filtered.size());
        log.info("=== RAG检索结束 ===");

        return buildRAGResult(filtered);
    }

    /**
     * RRF融合算法 - 将BM25和向量检索结果融合
     * 公式: score = sum(1 / (k + rank))
     */
    private List<VectorStore.SearchResult> fuseResults(
            List<BM25Service.BM25Result> bm25Results,
            List<VectorStore.SearchResult> vectorResults,
            int topK) {

        final double K = 60.0; // RRF常数
        Map<String, Double> fusedScores = new HashMap<>();
        Map<String, VectorStore.SearchResult> resultMap = new HashMap<>();

        // 处理向量检索结果
        for (int i = 0; i < vectorResults.size(); i++) {
            VectorStore.SearchResult r = vectorResults.get(i);
            double rrfScore = 1.0 / (K + i + 1);
            fusedScores.merge(r.getId(), rrfScore, Double::sum);
            resultMap.put(r.getId(), r);
        }

        // 处理BM25检索结果
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Service.BM25Result r = bm25Results.get(i);
            double rrfScore = 1.0 / (K + i + 1);
            fusedScores.merge(r.getId(), rrfScore, Double::sum);

            // 如果向量结果中没有这个chunk，需要创建新的SearchResult
            if (!resultMap.containsKey(r.getId())) {
                resultMap.put(r.getId(), VectorStore.SearchResult.builder()
                        .id(r.getId())
                        .documentId(r.getDocumentId()) // Fix: Map documentId
                        .content(r.getContent())
                        .score(0.0) // 临时分数，会被RRF分数替换
                        .metadata(r.getMetadata())
                        .build());
            }
        }

        // 按融合分数排序
        return fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> {
                    VectorStore.SearchResult r = resultMap.get(e.getKey());
                    // 用RRF分数更新
                    return VectorStore.SearchResult.builder()
                            .id(r.getId())
                            .documentId(r.getDocumentId())
                            .content(r.getContent())
                            .score(e.getValue())
                            .metadata(r.getMetadata())
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * 查询扩展 - 使用LLM将简短查询扩展为更丰富的表达
     */
    private String expandQuery(String query) {
        // 只对短查询进行扩展
        if (query == null || query.length() > 50) {
            return query;
        }

        try {
            String prompt = "请将以下用户问题重新表述，使其更加完整和清晰，保持原意但增加相关的同义词和关键词。" +
                    "只输出改写后的问题，不要输出其他内容。\n\n用户问题: " + query;

            List<LLMClient.Message> messages = new ArrayList<>();
            messages.add(LLMClient.Message.builder()
                    .role("user")
                    .content(prompt)
                    .build());

            LLMClient.ChatRequest request = LLMClient.ChatRequest.builder()
                    .messages(messages)
                    .maxTokens(100)
                    .temperature(0.3)
                    .build();

            LLMClient client = router.getClient(null);
            String expanded = client.chat(request);

            if (expanded != null && !expanded.trim().isEmpty()) {
                // 将原查询和扩展查询合并，确保原关键词也能匹配
                return query + " " + expanded.trim();
            }
        } catch (Exception e) {
            log.warn("查询扩展失败: {}", e.getMessage());
        }

        return query;
    }

    /**
     * 【Plan B】小文档直接模式
     * 如果文档较小（切片数量少），直接返回完整文档内容，跳过检索
     */
    private RAGResult trySmallDocumentMode(String documentId, String query) {
        int threshold = appProperties.getRag().getSmallDocumentThreshold();

        return documentRepository.findById(documentId)
                .filter(doc -> doc.getChunkCount() != null && doc.getChunkCount() <= threshold)
                .filter(doc -> doc.getFullText() != null && !doc.getFullText().isEmpty())
                .map(doc -> {
                    log.info("【Plan B】使用小文档直接模式: chunks={} <= threshold={}",
                            doc.getChunkCount(), threshold);

                    // 构建上下文 - 直接使用完整文档
                    String context = doc.getFullText();

                    // 构建单个引用
                    List<CitationDto> citations = new ArrayList<>();
                    citations.add(CitationDto.builder()
                            .chunkId("full-document")
                            .documentId(documentId) // Fix: Set documentId
                            .documentName(doc.getFilename())
                            .pageNumber(null)
                            .excerpt(truncate(context, 200))
                            .summary("完整文档内容（小文档直接模式）")
                            .score(1.0)
                            .build());

                    log.info("=== RAG检索结束 (小文档直接模式) ===");
                    return new RAGResult(context, citations);
                })
                .orElse(null);
    }

    private RAGResult buildRAGResult(List<VectorStore.SearchResult> results) {
        StringBuilder context = new StringBuilder();
        List<CitationDto> citations = new ArrayList<>();

        // 最多显示的引用数量
        final int maxCitations = 5;

        for (int i = 0; i < results.size(); i++) {
            VectorStore.SearchResult result = results.get(i);

            String documentPrefix = "";
            if (result.getMetadata() != null && result.getMetadata().containsKey("filename")) {
                documentPrefix = String.format("【文档：%s】", result.getMetadata().get("filename"));
            }

            // 构建上下文 - 所有结果都加入上下文，并附带文档名称
            context.append(String.format("[%d] %s%s\n\n", i + 1, documentPrefix, result.getContent()));

            // 只为前 maxCitations 个构建引用（节省 LLM 调用成本）
            if (i < maxCitations) {
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

                // 直接使用原文, 不再生成摘要以保证准确性
                citations.add(CitationDto.builder()
                        .chunkId(result.getId())
                        .documentId(result.getDocumentId()) // 设置文档ID
                        .documentName(documentName)
                        .pageNumber(pageNumber)
                        .excerpt(truncate(result.getContent(), 300))
                        .summary(null) // 不再生成摘要
                        .score(result.getScore())
                        .build());
            }
        }

        return new RAGResult(context.toString(), citations);
    }

    /**
     * 使用 LLM 生成文档块摘要
     */
    private String generateSummary(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }

        try {
            String systemPrompt = "你是一个专业的文档摘要助手。请用简洁的中文对以下内容生成一个摘要，突出关键信息。摘要应该在2-3句话内，不超过100字。";

            List<LLMClient.Message> messages = new ArrayList<>();
            messages.add(LLMClient.Message.builder()
                    .role("user")
                    .content("请为以下内容生成摘要：\n\n" + content)
                    .build());

            LLMClient.ChatRequest request = LLMClient.ChatRequest.builder()
                    .systemPrompt(systemPrompt)
                    .messages(messages)
                    .maxTokens(150)
                    .temperature(0.3)
                    .build();

            LLMClient client = router.getClient(null); // 使用默认模型
            String summary = client.chat(request);

            log.debug("Generated summary for chunk: {}", summary);
            return summary != null ? summary.trim() : truncate(content, 100);

        } catch (Exception e) {
            log.warn("Failed to generate summary, falling back to truncation: {}", e.getMessage());
            return truncate(content, 100);
        }
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
