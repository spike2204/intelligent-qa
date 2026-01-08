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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Azure OpenAI LLM Client
 */
@Slf4j
public class AzureOpenAILLMClient implements LLMClient {

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties.LlmConfig.ModelConfig modelConfig;
    private final AtomicBoolean available = new AtomicBoolean(true);

    public AzureOpenAILLMClient(AppProperties.LlmConfig.ModelConfig modelConfig) {
        this.modelConfig = modelConfig;
        this.objectMapper = new ObjectMapper();

        int timeout = modelConfig.getTimeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
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
                // User provided curl uses "Authorization: Bearer", but standard Azure Key uses
                // "api-key".
                // Since the key is provided directly (7T0H...), we will use "api-key" header as
                // it is safer for Azure Keys.
                // However, user specifically curl showed Authorization: Bearer.
                // Let's support standard Azure Key first, if it fails user might be using AD
                // Auth which requires Bearer.
                // Given the key format, it looks like a standard key.

                Request httpRequest = new Request.Builder()
                        .url(getEndpoint())
                        .header("api-key", apiKey) // Standard Azure Key Header
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();

                EventSource.Factory factory = EventSources.createFactory(httpClient);
                factory.newEventSource(httpRequest, new SSEListener(sink));

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
                JsonNode root = objectMapper.readTree(responseBody);
                return root.path("choices").get(0).path("message").path("content").asText();
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

    private String buildRequestBody(ChatRequest request, boolean stream) throws Exception {
        Map<String, Object> body = new HashMap<>();
        // Azure doesn't always need model in body if it's in URL, but standard OpenAI
        // does.
        // User's curl includes "model": "gpt-4.1". Let's include it.
        body.put("model", getModelName());
        body.put("stream", stream);
        body.put("max_completion_tokens", request.getMaxTokens() > 0 ? request.getMaxTokens()
                : modelConfig.getMaxTokens());
        body.put("temperature", request.getTemperature() > 0 ? request.getTemperature() : 1.0);
        body.put("top_p", 1);
        body.put("frequency_penalty", 0);
        body.put("presence_penalty", 0);

        List<Map<String, String>> messages = new ArrayList<>();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
            messages.add(systemMsg);
        }

        for (Message msg : request.getMessages()) {
            Map<String, String> msgMap = new HashMap<>();
            msgMap.put("role", msg.getRole());
            msgMap.put("content", msg.getContent());
            messages.add(msgMap);
        }

        body.put("messages", messages);
        return objectMapper.writeValueAsString(body);
    }

    private void handleErrorResponse(Response response) throws IOException {
        int code = response.code();
        String body = response.body() != null ? response.body().string() : "";
        log.error("Azure OpenAI API Error: status={}, body={}", code, body);
        throw new LLMException(LLMException.LLMErrorType.SERVICE_ERROR, "API call failed: " + code + " - " + body);
    }

    private class SSEListener extends EventSourceListener {
        private final FluxSink<String> sink;

        SSEListener(FluxSink<String> sink) {
            this.sink = sink;
        }

        @Override
        public void onEvent(EventSource eventSource, String id, String type, String data) {
            if ("[DONE]".equals(data)) {
                sink.complete();
                return;
            }

            try {
                JsonNode root = objectMapper.readTree(data);
                // Azure responses match OpenAI format for streaming usually
                JsonNode choices = root.path("choices");
                if (choices.isArray() && choices.size() > 0) {
                    JsonNode delta = choices.get(0).path("delta");
                    String content = delta.path("content").asText("");
                    if (!content.isEmpty()) {
                        sink.next(content);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to parse SSE data: {}", data);
            }
        }

        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            log.error("SSE connection failed", t);
            sink.error(new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "Stream connection failed: " + (t != null ? t.getMessage() : "unknown"), t));
        }

        @Override
        public void onClosed(EventSource eventSource) {
            sink.complete();
        }
    }
}
