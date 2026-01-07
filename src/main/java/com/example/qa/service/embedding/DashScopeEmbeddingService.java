package com.example.qa.service.embedding;

import com.example.qa.config.AppProperties;
import com.example.qa.exception.LLMException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 通义千问 Embedding服务 (DashScope API)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.embedding.type", havingValue = "dashscope")
public class DashScopeEmbeddingService implements EmbeddingService {
    
    private static final String API_URL = "https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding";
    private static final int DIMENSION = 1536;  // text-embedding-v2 维度
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    
    public DashScopeEmbeddingService(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public float[] embed(String text) {
        List<String> texts = new ArrayList<>();
        texts.add(text);
        List<float[]> result = embedBatch(texts);
        return result.isEmpty() ? new float[DIMENSION] : result.get(0);
    }
    
    @Override
    public List<float[]> embedBatch(List<String> texts) {
        try {
            String apiKey = appProperties.getEmbedding().getDashscope().getApiKey();
            String model = appProperties.getEmbedding().getDashscope().getModel();
            
            // 构建请求体
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("model", model);
            requestMap.put("input", texts);
            String requestBody = objectMapper.writeValueAsString(requestMap);
            
            Request request = new Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();
            
            Response response = httpClient.newCall(request).execute();
            try {
                if (!response.isSuccessful()) {
                    throw new LLMException(LLMException.LLMErrorType.SERVICE_ERROR,
                            "Embedding API调用失败: " + response.code());
                }
                
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                
                List<float[]> embeddings = new ArrayList<>();
                JsonNode dataNode = root.path("output").path("embeddings");
                
                for (JsonNode embNode : dataNode) {
                    JsonNode embArray = embNode.path("embedding");
                    float[] embedding = new float[embArray.size()];
                    for (int i = 0; i < embArray.size(); i++) {
                        embedding[i] = (float) embArray.get(i).asDouble();
                    }
                    embeddings.add(embedding);
                }
                
                return embeddings;
            } finally {
                response.close();
            }
        } catch (IOException e) {
            log.error("Embedding API调用异常", e);
            throw new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "Embedding服务调用失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public int getDimension() {
        return DIMENSION;
    }
}
