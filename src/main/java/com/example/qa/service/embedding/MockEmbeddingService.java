package com.example.qa.service.embedding;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mock Embedding服务 - 用于开发测试
 */
@Slf4j
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "app.embedding.type", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingService implements EmbeddingService {

    private static final int DIMENSION = 1024;
    private final Random random = new Random(42); // 固定种子保证可复现

    @Override
    public float[] embed(String text) {
        // 基于文本hash生成伪向量，相似文本会有相似向量
        float[] embedding = new float[DIMENSION];
        int hash = text.hashCode();
        Random textRandom = new Random(hash);

        for (int i = 0; i < DIMENSION; i++) {
            embedding[i] = (float) (textRandom.nextGaussian() * 0.1);
        }

        // 归一化
        float norm = 0;
        for (float v : embedding) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < DIMENSION; i++) {
            embedding[i] /= norm;
        }

        log.debug("生成Mock Embedding: 文本长度={}, 维度={}", text.length(), DIMENSION);
        return embedding;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        List<float[]> embeddings = new ArrayList<>();
        for (String text : texts) {
            embeddings.add(embed(text));
        }
        return embeddings;
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }
}
