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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
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
 * 通义千问 LLM客户端 (DashScope API)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.llm.primary.type", havingValue = "dashscope")
public class DashScopeLLMClient implements LLMClient {
    
    private static final String API_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final AtomicBoolean available = new AtomicBoolean(true);
    
    public DashScopeLLMClient(AppProperties appProperties) {
        this.appProperties = appProperties;
        this.objectMapper = new ObjectMapper();
        
        int timeout = appProperties.getLlm().getPrimary().getTimeout();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    @Override
    public String getModelName() {
        return appProperties.getLlm().getPrimary().getModel();
    }
    
    @Override
    public Flux<String> streamChat(ChatRequest request) {
        return Flux.create(sink -> {
            try {
                String requestBody = buildRequestBody(request, true);
                String apiKey = appProperties.getLlm().getPrimary().getApiKey();
                
                Request httpRequest = new Request.Builder()
                        .url(API_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                        .build();
                
                EventSource.Factory factory = EventSources.createFactory(httpClient);
                factory.newEventSource(httpRequest, new SSEListener(sink));
                
            } catch (Exception e) {
                log.error("流式请求构建失败", e);
                sink.error(new LLMException(LLMException.LLMErrorType.INVALID_REQUEST, 
                        "请求构建失败: " + e.getMessage(), e));
            }
        });
    }
    
    @Override
    public String chat(ChatRequest request) {
        try {
            String requestBody = buildRequestBody(request, false);
            String apiKey = appProperties.getLlm().getPrimary().getApiKey();
            
            Request httpRequest = new Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .build();
            
            Response response = httpClient.newCall(httpRequest).execute();
            try {
                if (!response.isSuccessful()) {
                    handleErrorResponse(response);
                }
                
                String responseBody = response.body().string();
                JsonNode root = objectMapper.readTree(responseBody);
                return root.path("choices").get(0).path("message").path("content").asText();
            } finally {
                response.close();
            }
        } catch (Exception e) {
            log.error("LLM调用失败", e);
            throw new LLMException(LLMException.LLMErrorType.NETWORK_ERROR, 
                    "网络请求失败: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isAvailable() {
        return available.get();
    }
    
    private String buildRequestBody(ChatRequest request, boolean stream) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("model", getModelName());
        body.put("stream", stream);
        body.put("max_tokens", request.getMaxTokens() > 0 ? request.getMaxTokens() : 2048);
        body.put("temperature", request.getTemperature() > 0 ? request.getTemperature() : 0.7);
        
        List<Map<String, String>> messages = new ArrayList<>();
        
        // 添加系统提示
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            Map<String, String> systemMsg = new HashMap<>();
            systemMsg.put("role", "system");
            systemMsg.put("content", request.getSystemPrompt());
            messages.add(systemMsg);
        }
        
        // 添加对话消息
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
        
        LLMException.LLMErrorType errorType;
        switch (code) {
            case 401:
            case 403:
                errorType = LLMException.LLMErrorType.AUTH_ERROR;
                break;
            case 429:
                errorType = LLMException.LLMErrorType.RATE_LIMIT;
                break;
            case 408:
            case 504:
                errorType = LLMException.LLMErrorType.TIMEOUT;
                break;
            default:
                errorType = LLMException.LLMErrorType.SERVICE_ERROR;
        }
        
        throw new LLMException(errorType, "API调用失败: " + code + " - " + body);
    }
    
    /**
     * SSE事件监听器
     */
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
                JsonNode delta = root.path("choices").get(0).path("delta");
                String content = delta.path("content").asText("");
                
                if (!content.isEmpty()) {
                    sink.next(content);
                }
            } catch (Exception e) {
                log.warn("解析SSE数据失败: {}", data);
            }
        }
        
        @Override
        public void onFailure(EventSource eventSource, Throwable t, Response response) {
            log.error("SSE连接失败", t);
            sink.error(new LLMException(LLMException.LLMErrorType.NETWORK_ERROR,
                    "流式连接失败: " + (t != null ? t.getMessage() : "unknown"), t));
        }
        
        @Override
        public void onClosed(EventSource eventSource) {
            sink.complete();
        }
    }
}
