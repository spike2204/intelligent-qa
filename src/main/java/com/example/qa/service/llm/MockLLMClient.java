package com.example.qa.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * Mock LLM客户端 - 用于开发测试
 */
@Slf4j
@Component
public class MockLLMClient implements LLMClient {
    
    @Override
    public String getModelName() {
        return "mock-model";
    }
    
    @Override
    public Flux<String> streamChat(ChatRequest request) {
        String response = generateMockResponse(request);
        
        // 模拟流式输出，每个字符间隔50ms
        return Flux.fromArray(response.split(""))
                .delayElements(Duration.ofMillis(30))
                .doOnSubscribe(s -> log.debug("Mock LLM开始流式输出"))
                .doOnComplete(() -> log.debug("Mock LLM流式输出完成"));
    }
    
    @Override
    public String chat(ChatRequest request) {
        return generateMockResponse(request);
    }
    
    @Override
    public boolean isAvailable() {
        return true;
    }
    
    private String generateMockResponse(ChatRequest request) {
        // 从最后一条用户消息中提取问题
        List<Message> messages = request.getMessages();
        String userQuestion = "";
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).getRole())) {
                userQuestion = messages.get(i).getContent();
                break;
            }
        }
        
        return String.format(
                "这是一个模拟的AI回答。\n\n" +
                "您的问题是：「%s」\n\n" +
                "根据提供的文档内容，以下是相关信息：\n\n" +
                "1. 文档中提到了相关的概念和定义。\n" +
                "2. 具体的实现细节可以参考文档的详细说明。\n" +
                "3. 如需更多信息，建议查阅完整文档。\n\n" +
                "【注意】这是开发模式下的模拟响应，请配置真实的LLM API以获取准确答案。",
                userQuestion.length() > 50 ? userQuestion.substring(0, 50) + "..." : userQuestion
        );
    }
}
