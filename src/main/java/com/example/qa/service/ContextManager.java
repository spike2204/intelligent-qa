package com.example.qa.service;

import com.example.qa.config.AppProperties;
import com.example.qa.entity.ChatMessage;
import com.example.qa.entity.ChatSession;
import com.example.qa.repository.ChatMessageRepository;
import com.example.qa.repository.ChatSessionRepository;
import com.example.qa.service.llm.LLMClient;
import com.example.qa.service.llm.LLMRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 上下文管理服务 - 维护对话历史和长记忆
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextManager {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final LLMRouter llmRouter;
    private final AppProperties appProperties;

    /**
     * 创建新会话
     */
    public ChatSession createSession(String documentId) {
        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setDocumentIds(documentId); // 支持单个或多个文档ID(逗号分隔)
        session.setMessageCount(0);
        return sessionRepository.save(session);
    }

    /**
     * 保存消息
     */
    public void saveMessage(String sessionId, ChatMessage.MessageRole role, String content) {
        ChatMessage message = new ChatMessage();
        message.setId(UUID.randomUUID().toString());
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setTokenCount(estimateTokens(content));
        messageRepository.save(message);

        // 更新会话消息计数
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.setMessageCount(session.getMessageCount() + 1);
            sessionRepository.save(session);

            // 检查是否需要摘要压缩
            if (session.getMessageCount() >= appProperties.getContext().getSummaryThreshold() * 2) {
                summarizeHistory(session);
            }
        });
    }

    /**
     * 获取上下文消息列表(用于构建LLM请求)
     */
    public List<LLMClient.Message> getContextMessages(String sessionId, int maxTokens) {
        List<LLMClient.Message> result = new ArrayList<>();
        int totalTokens = 0;

        // 获取会话摘要
        ChatSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session != null && session.getSummary() != null) {
            int summaryTokens = estimateTokens(session.getSummary());
            if (totalTokens + summaryTokens < maxTokens) {
                result.add(LLMClient.Message.builder()
                        .role("system")
                        .content("之前的对话摘要：" + session.getSummary())
                        .build());
                totalTokens += summaryTokens;
            }
        }

        // 获取最近的对话历史(倒序获取，然后反转)
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        List<LLMClient.Message> recentMessages = new ArrayList<>();

        for (ChatMessage msg : messages) {
            int msgTokens = msg.getTokenCount() != null ? msg.getTokenCount() : estimateTokens(msg.getContent());
            if (totalTokens + msgTokens > maxTokens)
                break;

            recentMessages.add(0, LLMClient.Message.builder()
                    .role(msg.getRole().name().toLowerCase())
                    .content(msg.getContent())
                    .build());
            totalTokens += msgTokens;
        }

        result.addAll(recentMessages);
        log.debug("构建上下文: sessionId={}, 消息数={}, 总token≈{}",
                sessionId, result.size(), totalTokens);

        return result;
    }

    /**
     * 对历史对话进行摘要压缩
     */
    private void summarizeHistory(ChatSession session) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        if (messages.size() < appProperties.getContext().getSummaryThreshold()) {
            return;
        }

        // 保留最近N轮，对之前的进行摘要
        int keepCount = appProperties.getContext().getMaxHistoryRounds() * 2; // 每轮2条消息
        if (messages.size() <= keepCount)
            return;

        List<ChatMessage> toSummarize = messages.subList(0, messages.size() - keepCount);

        // 构建摘要请求
        StringBuilder historyText = new StringBuilder("请将以下对话历史压缩为简短摘要，保留关键信息：\n\n");
        for (ChatMessage msg : toSummarize) {
            historyText.append(msg.getRole().name()).append(": ").append(msg.getContent()).append("\n");
        }

        try {
            LLMClient client = llmRouter.getPrimary();
            List<LLMClient.Message> msgList = new ArrayList<>();
            msgList.add(LLMClient.Message.builder()
                    .role("user")
                    .content(historyText.toString())
                    .build());

            String summary = client.chat(LLMClient.ChatRequest.builder()
                    .messages(msgList)
                    .maxTokens(500)
                    .temperature(0.3)
                    .build());

            // 更新会话摘要
            String existingSummary = session.getSummary();
            session.setSummary(existingSummary != null ? existingSummary + "\n" + summary : summary);
            sessionRepository.save(session);

            // 删除已摘要的消息
            for (ChatMessage msg : toSummarize) {
                messageRepository.delete(msg);
            }

            log.info("对话摘要完成: sessionId={}, 压缩{}条消息", session.getId(), toSummarize.size());

        } catch (Exception e) {
            log.warn("对话摘要失败: {}", e.getMessage());
        }
    }

    /**
     * 清除会话
     */
    public void clearSession(String sessionId) {
        messageRepository.deleteBySessionId(sessionId);
        sessionRepository.deleteById(sessionId);
    }

    private int estimateTokens(String text) {
        if (text == null)
            return 0;
        // 简单估算: 中文1字≈1token，英文4字符≈1token
        int chinese = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5]"))
                chinese++;
            else
                other++;
        }
        return chinese + other / 4;
    }
}
