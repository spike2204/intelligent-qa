package com.example.qa.service.llm;

import com.example.qa.config.AppProperties;
import com.example.qa.exception.LLMException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Azure OpenAI Responses API Client (新版API格式)
 * 支持 gpt-5.x 等新模型
 */
@Slf4j
public class AzureResponsesLLMClient implements LLMClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties.LlmConfig.ModelConfig modelConfig;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public AzureResponsesLLMClient(AppProperties.LlmConfig.ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
        this.objectMapper = new ObjectMapper();

        int timeout = modelConfig.getTimeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        log.info("AzureResponsesLLMClient initialized for model: {}", getModelName());
    }

    @Override
    public String getModelName() {
        return modelConfig.getModel();
    }

    private String getEndpoint() {
        return modelConfig.getEndpoint();
    }

    @Override
    public Flux<String> streamChat(ChatRequest request) {
        return Flux.create(sink -> {
            try {
                String requestBody = buildRequestBody(request, true);
                String apiKey = modelConfig.getApiKey();

                Request httpRequest = new Request.Builder()
                        .url(getEndpoint())
                        .header("api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                EventSource.Factory factory = EventSources.createFactory(httpClient);
                factory.newEventSource(httpRequest, new ResponsesSSEListener(sink));

            } catch (Exception e) {
                log.error("Failed to build stream request", e);
                sink.error(new LLMException(LLMException.LLMErrorType.INVALID_REQUEST,
                        "Request build failed: " + e.getMessage(), e));
            }
        });
    }

    @Override
    public String chat(ChatRequest request) {
        try {
            String requestBody = buildRequestBody(request, false);
            String apiKey = modelConfig.getApiKey();

            log.debug("Responses API Request: {}", requestBody);

            Request httpRequest = new Request.Builder()
                    .url(getEndpoint())
                    .header("api-key", apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    handleErrorResponse(response);
                }

                String responseBody = response.body().string();
                log.debug("Responses API Response: {}", responseBody);

                return parseResponseContent(responseBody);
            }
        } catch (Exception e) {
            log.error("LLM call failed", e);
            throw new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "Network request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isAvailable() {
        return available.get();
    }

    /**
     * 构建 Responses API 请求体
     * 格式: {"model": "gpt-4.1", "input": "...", "instructions": "..."}
     */
    private String buildRequestBody(ChatRequest request, boolean stream) throws Exception {
        Map<String, Object> body = new HashMap<>();

        // 支持动态模型切换
        String model = (request.getModelOverride() != null && !request.getModelOverride().isEmpty())
                ? request.getModelOverride()
                : getModelName();
        body.put("model", model);
        body.put("stream", stream);

        log.info("使用模型: {}", model);

        if (request.getMaxTokens() > 0) {
            body.put("max_output_tokens", request.getMaxTokens());
        }
        if (request.getMaxTokens() > 0) {
            body.put("max_output_tokens", request.getMaxTokens());
        }

        // GPT-5.2 / Responses API preview might not support sampling parameters
        // body.put("temperature", request.getTemperature());
        // body.put("top_p", 1);
        // body.put("frequency_penalty", 0);
        // body.put("presence_penalty", 0);

        // Responses API 使用 input 和 instructions 而非 messages
        StringBuilder inputBuilder = new StringBuilder();

        // System prompt 作为 instructions
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            body.put("instructions", request.getSystemPrompt());
        }

        // 构建用户输入
        for (Message msg : request.getMessages()) {
            if ("user".equals(msg.getRole())) {
                inputBuilder.append(msg.getContent()).append("\n");
            } else if ("assistant".equals(msg.getRole())) {
                inputBuilder.append("[助手回复]: ").append(msg.getContent()).append("\n");
            }
        }

        body.put("input", inputBuilder.toString().trim());

        return objectMapper.writeValueAsString(body);
    }

    /**
     * 解析 Responses API 返回内容
     */
    private String parseResponseContent(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);

        // 检查错误
        if (root.has("error") && !root.path("error").isNull()) {
            String errorMsg = root.path("error").path("message").asText("Unknown error");
            throw new LLMException(LLMException.LLMErrorType.SERVICE_ERROR, "API Error: " + errorMsg);
        }

        // Responses API 格式: output[0].content[0].text
        JsonNode output = root.path("output");
        if (output.isArray() && output.size() > 0) {
            JsonNode content = output.get(0).path("content");
            if (content.isArray() && content.size() > 0) {
                return content.get(0).path("text").asText("");
            }
        }

        log.warn("Unexpected response format: {}", responseBody);
        return "";
    }

    private void handleErrorResponse(Response response) throws IOException {
        int code = response.code();
        String body = response.body() != null ? response.body().string() : "";
        log.error("Azure Responses API Error: status={}, body={}", code, body);
        throw new LLMException(LLMException.LLMErrorType.SERVICE_ERROR,
                "API call failed: " + code + " - " + body);
    }

    /**
     * SSE Listener for Responses API streaming
     */
    private class ResponsesSSEListener extends EventSourceListener {
        private final FluxSink<String> sink;

        ResponsesSSEListener(FluxSink<String> sink) {
            this.sink = sink;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            if ("[DONE]".equals(data) || "response.completed".equals(type)) {
                sink.complete();
                return;
            }

            try {
                JsonNode root = objectMapper.readTree(data);

                // Responses API 流式格式可能有差异，尝试多种解析
                // 格式1: delta in output_text
                if (root.has("delta")) {
                    String delta = root.path("delta").asText("");
                    if (!delta.isEmpty()) {
                        sink.next(delta);
                        return;
                    }
                }

                // 格式2: content in output
                JsonNode output = root.path("output");
                if (output.isArray() && output.size() > 0) {
                    JsonNode content = output.get(0).path("content");
                    if (content.isArray() && content.size() > 0) {
                        String text = content.get(0).path("text").asText("");
                        if (!text.isEmpty()) {
                            sink.next(text);
                        }
                    }
                }

            } catch (Exception e) {
                log.warn("Failed to parse SSE data: {}", data);
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            String errorBody = "";
            if (response != null && response.body() != null) {
                try {
                    errorBody = response.body().string();
                } catch (IOException e) {
                    log.error("Failed to read error response body", e);
                }
            }
            log.error("SSE connection failed. Body: {}", errorBody, t);
            sink.error(new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "Stream connection failed: " + (t != null ? t.getMessage() : "unknown") + ", Body: " + errorBody,
                    t));
        }

        @Override
        public void onClosed(EventSource eventSource) {
            sink.complete();
        }
    }
}
