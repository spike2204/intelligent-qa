package com.example.qa.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用配置属性
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private DocumentConfig document = new DocumentConfig();
    private ChunkingConfig chunking = new ChunkingConfig();
    private VectorConfig vector = new VectorConfig();
    private EmbeddingConfig embedding = new EmbeddingConfig();
    private LlmConfig llm = new LlmConfig();
    private ContextConfig context = new ContextConfig();
    private RagConfig rag = new RagConfig();

    @Data
    public static class DocumentConfig {
        private String storagePath = "./uploads";
        private long maxFileSize = 52428800L;
        private String allowedTypes = "pdf,md,markdown,txt";
    }

    @Data
    public static class ChunkingConfig {
        private int chunkSize = 500;
        private int chunkOverlap = 50;
        private int minChunkSize = 100;
    }

    @Data
    public static class VectorConfig {
        private String type = "memory";
        private MilvusConfig milvus = new MilvusConfig();

        @Data
        public static class MilvusConfig {
            private String host = "localhost";
            private int port = 19530;
            private String collectionName = "document_chunks";
            private int dimension = 1024;
        }
    }

    @Data
    public static class EmbeddingConfig {
        private String type = "mock";
        private DashScopeConfig dashscope = new DashScopeConfig();
        private OpenAIConfig openai = new OpenAIConfig();
        private AzureConfig azure = new AzureConfig();

        @Data
        public static class DashScopeConfig {
            private String apiKey;
            private String model = "text-embedding-v2";
        }

        @Data
        public static class OpenAIConfig {
            private String apiKey;
            private String model = "text-embedding-3-small";
        }

        @Data
        public static class AzureConfig {
            private String apiKey;
            private String endpoint;
            private String deploymentName = "text-embedding-ada-002"; // default
        }
    }

    @Data
    public static class LlmConfig {
        private ModelConfig primary = new ModelConfig();
        private ModelConfig fallback = new ModelConfig();
        private RetryConfig retry = new RetryConfig();

        @Data
        public static class ModelConfig {
            private String type = "mock";
            private String apiType = "chat"; // chat (传统) 或 responses (新版API)
            private String apiKey;
            private String model;
            private String endpoint; // Azure OpenAI Endpoint
            private String apiVersion; // Azure OpenAI API Version
            private int timeout = 60000;
            private int maxTokens = 2048;
        }

        @Data
        public static class RetryConfig {
            private int maxAttempts = 3;
            private long delayMs = 1000;
            private double multiplier = 2.0;
        }
    }

    @Data
    public static class ContextConfig {
        private int maxHistoryRounds = 10;
        private int maxContextTokens = 4000;
        private int summaryThreshold = 6;
    }

    @Data
    public static class RagConfig {
        private int topK = 5;
        private double similarityThreshold = 0.7;
        private boolean rerankEnabled = false;
        private boolean contextualRetrievalEnabled = false; // 上下文增强开关
        private int smallDocumentThreshold = 10; // 小于此切片数量时使用直接模式
    }
}
