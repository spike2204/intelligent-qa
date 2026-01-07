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
 * Azure OpenAI Embedding Service
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.embedding.type", havingValue = "azure")
public class AzureEmbeddingService implements EmbeddingService {

    private static final int DIMENSION = 1536; // text-embedding-ada-002

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public AzureEmbeddingService(AppProperties appProperties) {
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
            String apiKey = appProperties.getEmbedding().getAzure().getApiKey();
            String endpoint = appProperties.getEmbedding().getAzure().getEndpoint();

            // Build Request Body
            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("input", texts);
            String requestBody = objectMapper.writeValueAsString(requestMap);

            Request request = new Request.Builder()
                    .url(endpoint)
                    .header("api-key", apiKey) // Azure uses api-key header by default
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    log.error("Azure Embedding API failed: code={}, body={}", response.code(), body);
                    throw new LLMException(LLMException.LLMErrorType.SERVICE_ERROR,
                            "Embedding API call failed: " + response.code());
                }

                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);

                List<float[]> embeddings = new ArrayList<>();
                JsonNode dataNode = root.path("data");

                for (JsonNode itemNode : dataNode) {
                    JsonNode embArray = itemNode.path("embedding");
                    float[] embedding = new float[embArray.size()];
                    for (int i = 0; i < embArray.size(); i++) {
                        embedding[i] = (float) embArray.get(i).asDouble();
                    }
                    embeddings.add(embedding);
                }

                return embeddings;
            }
        } catch (IOException e) {
            log.error("Azure Embedding API Exception", e);
            throw new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "Embedding Service failed: " + e.getMessage(), e);
        }
    }

    @Override
    public int getDimension() {
        return DIMENSION;
    }
}
