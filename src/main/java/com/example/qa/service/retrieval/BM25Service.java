package com.example.qa.service.retrieval;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BM25关键词检索服务
 * 实现基于BM25算法的关键词检索，与向量检索配合使用
 */
@Slf4j
@Service
public class BM25Service {

    // BM25参数
    private static final double K1 = 1.2; // 词频饱和参数
    private static final double B = 0.75; // 文档长度归一化参数

    // 文档索引存储: documentId -> (chunkId -> ChunkIndex)
    private final Map<String, Map<String, ChunkIndex>> documentIndex = new ConcurrentHashMap<>();

    // 平均文档长度
    private final Map<String, Double> avgDocLength = new ConcurrentHashMap<>();

    /**
     * 索引文档块
     */
    public void indexChunks(String documentId, List<ChunkData> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        Map<String, ChunkIndex> chunkIndexMap = new HashMap<>();
        double totalLength = 0;

        for (ChunkData chunk : chunks) {
            List<String> tokens = tokenize(chunk.getContent());
            Map<String, Integer> termFreq = new HashMap<>();

            for (String token : tokens) {
                termFreq.merge(token, 1, Integer::sum);
            }

            chunkIndexMap.put(chunk.getId(), ChunkIndex.builder()
                    .id(chunk.getId())
                    .content(chunk.getContent())
                    .metadata(chunk.getMetadata())
                    .termFrequency(termFreq)
                    .length(tokens.size())
                    .build());

            totalLength += tokens.size();
        }

        documentIndex.put(documentId, chunkIndexMap);
        avgDocLength.put(documentId, totalLength / chunks.size());

        log.info("BM25索引完成: documentId={}, chunks={}", documentId, chunks.size());
    }

    /**
     * BM25检索
     */
    public List<BM25Result> search(String query, String documentId, int topK) {
        Map<String, ChunkIndex> chunkIndex = documentIndex.get(documentId);
        if (chunkIndex == null || chunkIndex.isEmpty()) {
            log.warn("BM25: 未找到文档索引: {}", documentId);
            return Collections.emptyList();
        }

        List<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return Collections.emptyList();
        }

        double avgLen = avgDocLength.getOrDefault(documentId, 100.0);
        int N = chunkIndex.size();

        // 计算IDF
        Map<String, Double> idf = new HashMap<>();
        for (String term : queryTokens) {
            long df = chunkIndex.values().stream()
                    .filter(c -> c.getTermFrequency().containsKey(term))
                    .count();
            // IDF公式: log((N - df + 0.5) / (df + 0.5) + 1)
            double idfValue = Math.log((N - df + 0.5) / (df + 0.5) + 1);
            idf.put(term, idfValue);
        }

        // 计算每个chunk的BM25分数
        List<BM25Result> results = new ArrayList<>();
        for (ChunkIndex chunk : chunkIndex.values()) {
            double score = 0.0;
            for (String term : queryTokens) {
                int tf = chunk.getTermFrequency().getOrDefault(term, 0);
                if (tf > 0) {
                    double idfValue = idf.getOrDefault(term, 0.0);
                    // BM25公式
                    double numerator = tf * (K1 + 1);
                    double denominator = tf + K1 * (1 - B + B * chunk.getLength() / avgLen);
                    score += idfValue * (numerator / denominator);
                }
            }

            if (score > 0) {
                results.add(BM25Result.builder()
                        .id(chunk.getId())
                        .documentId(documentId) // populate documentId
                        .content(chunk.getContent())
                        .metadata(chunk.getMetadata())
                        .score(score)
                        .build());
            }
        }

        // 按分数排序并返回topK
        return results.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * BM25多文档检索
     */
    public List<BM25Result> search(String query, List<String> documentIds, int topK) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 简单的聚合策略：分别检索每个文档，然后合并排序
        List<BM25Result> allResults = new ArrayList<>();

        // 为了避免单个文档占满结果，稍微放宽每个文档的检索数量
        int perDocTopK = Math.max(topK, 5);

        for (String docId : documentIds) {
            allResults.addAll(search(query, docId, perDocTopK));
        }

        return allResults.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .distinct() // 理论上ID唯一，但以防万一
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 删除文档索引
     */
    public void deleteByDocumentId(String documentId) {
        documentIndex.remove(documentId);
        avgDocLength.remove(documentId);
        log.info("BM25索引已删除: documentId={}", documentId);
    }

    /**
     * 简单分词 - 支持中英文混合
     * 对于中文采用单字分词，英文采用空白分词
     */
    private List<String> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> tokens = new ArrayList<>();
        StringBuilder englishWord = new StringBuilder();

        for (char c : text.toLowerCase().toCharArray()) {
            if (Character.isLetter(c)) {
                if (isChinese(c)) {
                    // 先保存之前的英文单词
                    if (englishWord.length() > 0) {
                        tokens.add(englishWord.toString());
                        englishWord = new StringBuilder();
                    }
                    // 中文单字作为token
                    tokens.add(String.valueOf(c));
                } else {
                    // 英文字符累积
                    englishWord.append(c);
                }
            } else if (Character.isDigit(c)) {
                englishWord.append(c);
            } else {
                // 非字母数字字符作为分隔
                if (englishWord.length() > 0) {
                    tokens.add(englishWord.toString());
                    englishWord = new StringBuilder();
                }
            }
        }

        // 处理最后的英文单词
        if (englishWord.length() > 0) {
            tokens.add(englishWord.toString());
        }

        return tokens;
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkData {
        private String id;
        private String content;
        private Map<String, Object> metadata;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkIndex {
        private String id;
        private String content;
        private Map<String, Object> metadata;
        private Map<String, Integer> termFrequency;
        private int length;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BM25Result {
        private String id;
        private String documentId; // Added documentId
        private String content;
        private Map<String, Object> metadata;
        private double score;
    }
}
