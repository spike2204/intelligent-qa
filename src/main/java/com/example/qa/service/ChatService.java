package com.example.qa.service;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.ChatChunkDto;
import com.example.qa.dto.CitationDto;
import com.example.qa.entity.ChatMessage;
import com.example.qa.entity.ChatSession;
import com.example.qa.repository.ChatSessionRepository;
import com.example.qa.service.llm.LLMClient;
import com.example.qa.service.llm.LLMRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天服务 - 负责问答流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final RAGService ragService;
    private final ContextManager contextManager;
    private final LLMRouter llmRouter;
    private final AppProperties appProperties;
    private final ChatSessionRepository sessionRepository;

    private static final String SYSTEM_PROMPT_TEMPLATE = "你是一个专业的文档问答助手。请根据以下提供的文档内容回答用户的问题。\n\n" +
            "要求：\n" +
            "1. 只根据提供的文档内容回答，不要编造信息\n" +
            "2. 如果文档中没有相关信息，请明确说明\n" +
            "3. 回答要准确、简洁、有条理\n" +
            "4. 在回答中适当引用文档内容\n\n" +
            "文档内容：\n%s";

    private static final String PURE_CHAT_PROMPT = "你是一个智能助手。请直接回答用户的问题，无需参考任何文档。";

    /**
     * 创建新会话
     */
    public ChatSession createSession(String documentId) {
        return contextManager.createSession(documentId);
    }

    /**
     * 流式问答
     */
    public Flux<ChatChunkDto> streamAnswer(String query, String sessionId, String documentId, String modelType) {
        // 保存用户问题
        contextManager.saveMessage(sessionId, ChatMessage.MessageRole.USER, query);

        // 如果前端没有传递documentId，从session中获取
        String resolvedDocumentId = resolveDocumentId(sessionId, documentId);

        // RAG检索 (仅当有documentId时)
        String ragContext = "";
        List<CitationDto> citationsList = new ArrayList<>();

        if (resolvedDocumentId != null && !resolvedDocumentId.isEmpty()) {
            RAGService.RAGResult ragResult = ragService.retrieve(query, resolvedDocumentId);
            ragContext = ragResult.getContext();
            citationsList = ragResult.getCitations();
        }

        // Final variable for lambda capture
        final List<CitationDto> finalCitations = citationsList;

        // 构建LLM请求
        // 构建LLM请求
        LLMClient.ChatRequest request = buildRequest(query, sessionId, ragContext, resolvedDocumentId);

        // 流式调用LLM
        LLMClient client = llmRouter.getClient(modelType);
        final StringBuilder fullResponse = new StringBuilder();

        return client.streamChat(request)
                .map(chunk -> {
                    fullResponse.append(chunk);
                    return ChatChunkDto.builder()
                            .content(chunk)
                            .complete(false)
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    // 保存助手回复
                    contextManager.saveMessage(sessionId, ChatMessage.MessageRole.ASSISTANT,
                            fullResponse.toString());

                    // 返回完成标记和引用
                    return Flux.just(ChatChunkDto.builder()
                            .content("")
                            .complete(true)
                            .citations(finalCitations)
                            .build());
                }))
                .onErrorResume(e -> {
                    log.error("Primary model stream failed: {}, attempting fallback", e.getMessage());

                    // 尝试降级
                    try {
                        LLMClient fallbackClient = llmRouter.fallback(client);
                        if (fallbackClient == client) {
                            throw new RuntimeException("No fallback available");
                        }

                        String warningMsg = String.format("模型 %s 响应超时，已自动切换至 %s 继续回答...",
                                client.getModelName(), fallbackClient.getModelName());

                        // 发送切模警告
                        return Flux.just(ChatChunkDto.builder()
                                .content("")
                                .complete(false)
                                .warning(warningMsg)
                                .build())
                                .concatWith(fallbackClient.streamChat(request)
                                        .map(chunk -> {
                                            fullResponse.append(chunk);
                                            return ChatChunkDto.builder()
                                                    .content(chunk)
                                                    .complete(false)
                                                    .build();
                                        }))
                                .concatWith(Flux.defer(() -> {
                                    contextManager.saveMessage(sessionId, ChatMessage.MessageRole.ASSISTANT,
                                            fullResponse.toString());
                                    return Flux.just(ChatChunkDto.builder()
                                            .content("")
                                            .complete(true)
                                            .citations(finalCitations)
                                            .build());
                                }));

                    } catch (Exception ex) {
                        return Flux.just(ChatChunkDto.builder()
                                .content("")
                                .complete(true)
                                .error("回答生成失败: " + e.getMessage())
                                .build());
                    }
                });
    }

    /**
     * 同步问答(非流式)
     */
    public ChatChunkDto answer(String query, String sessionId, String documentId, String modelType) {
        // 保存用户问题
        contextManager.saveMessage(sessionId, ChatMessage.MessageRole.USER, query);

        // 如果前端没有传递documentId，从session中获取
        String resolvedDocumentId = resolveDocumentId(sessionId, documentId);

        // RAG检索
        String ragContext = "";
        List<CitationDto> citations = new ArrayList<>();

        if (resolvedDocumentId != null && !resolvedDocumentId.isEmpty()) {
            RAGService.RAGResult ragResult = ragService.retrieve(query, resolvedDocumentId);
            ragContext = ragResult.getContext();
            citations = ragResult.getCitations();
        }

        // 构建并发送请求
        // 构建并发送请求
        LLMClient.ChatRequest request = buildRequest(query, sessionId, ragContext, resolvedDocumentId);
        LLMClient client = llmRouter.getClient(modelType);

        try {
            String response = client.chat(request);

            // 保存回复
            contextManager.saveMessage(sessionId, ChatMessage.MessageRole.ASSISTANT, response);

            return ChatChunkDto.builder()
                    .content(response)
                    .complete(true)
                    .citations(citations)
                    .build();

        } catch (Exception e) {
            log.error("问答失败", e);

            // 尝试降级
            try {
                LLMClient fallback = llmRouter.fallback(client);
                String response = fallback.chat(request);
                contextManager.saveMessage(sessionId, ChatMessage.MessageRole.ASSISTANT, response);

                return ChatChunkDto.builder()
                        .content(response + "\n\n[注: 使用了备用模型回答]")
                        .complete(true)
                        .citations(citations)
                        .build();
            } catch (Exception e2) {
                return ChatChunkDto.builder()
                        .content("")
                        .complete(true)
                        .error("回答生成失败: " + e2.getMessage())
                        .build();
            }
        }
    }

    /**
     * 解析documentId - 如果请求中没有传递，则从session中获取
     */
    private String resolveDocumentId(String sessionId, String documentId) {
        // 如果前端传递了有效的documentId，直接使用
        if (documentId != null && !documentId.isEmpty() && !"null".equalsIgnoreCase(documentId)) {
            return documentId;
        }

        // 否则从session中获取保存的documentId
        if (sessionId != null) {
            ChatSession session = sessionRepository.findById(sessionId).orElse(null);
            if (session != null && session.getDocumentId() != null) {
                log.debug("从session中获取documentId: sessionId={}, documentId={}",
                        sessionId, session.getDocumentId());
                return session.getDocumentId();
            }
        }

        return null;
    }

    private LLMClient.ChatRequest buildRequest(String query, String sessionId, String ragContext, String documentId) {
        // 获取历史上下文
        int maxContextTokens = appProperties.getContext().getMaxContextTokens();
        List<LLMClient.Message> contextMessages = contextManager.getContextMessages(sessionId, maxContextTokens / 2);

        // 添加当前问题
        List<LLMClient.Message> messages = new ArrayList<>(contextMessages);
        messages.add(LLMClient.Message.builder()
                .role("user")
                .content(query)
                .build());

        String systemPrompt;
        if (ragContext != null && !ragContext.isEmpty()) {
            systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, ragContext);
        } else if (documentId != null && !documentId.isEmpty() && !"null".equalsIgnoreCase(documentId)) {
            // RAG triggered but no context found (low score or empty store)
            systemPrompt = "You are a helpful assistant. The user asked a question about a document, but the retrieval system found NO relevant content (similarity too low or vector store empty). \n"
                    +
                    "Please politely inform the user that you couldn't find specific information in the uploaded document regarding their query.\n"
                    +
                    "Then, ONLY if you have general knowledge about the topic, you may answer but MUST start with 'Based on general knowledge (not the document)...'.";
        } else {
            systemPrompt = PURE_CHAT_PROMPT;
        }

        return LLMClient.ChatRequest.builder()
                .systemPrompt(systemPrompt)
                .messages(messages)
                .maxTokens(appProperties.getLlm().getPrimary().getMaxTokens())
                .temperature(0.7)
                .build();
    }
}
