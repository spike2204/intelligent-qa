package com.example.qa.service.document;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.ChunkDto;
import com.example.qa.service.llm.LLMClient;
import com.example.qa.service.llm.LLMRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文增强服务 - 基于 Anthropic Contextual Retrieval 方法
 * 
 * 为每个文档块生成上下文前缀，提升检索准确性。
 * 参考: https://www.anthropic.com/news/contextual-retrieval
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextualEnrichmentService {

    private final LLMRouter llmRouter;
    private final AppProperties appProperties;

    // 上下文生成的系统提示词
    private static final String CONTEXT_SYSTEM_PROMPT = "你是一个专业的文档分析助手。你的任务是为文档片段生成简短的上下文说明，" +
            "帮助理解该片段在整个文档中的位置和背景。";

    // 上下文生成的用户提示词模板
    private static final String CONTEXT_USER_PROMPT_TEMPLATE = "<document>\n%s\n</document>\n\n" +
            "以下是需要定位上下文的文档片段：\n" +
            "<chunk>\n%s\n</chunk>\n\n" +
            "请为这个片段生成一句简短的上下文说明（不超过50字），说明它在文档中的位置和主题。" +
            "只输出上下文说明，不要输出其他内容。";

    /**
     * 为单个 chunk 生成上下文前缀
     * 
     * @param fullDocument 完整文档文本
     * @param chunkContent chunk 内容
     * @return 上下文前缀
     */
    public String enrichChunk(String fullDocument, String chunkContent) {
        try {
            // 截断文档以避免超过 token 限制
            String truncatedDoc = truncateDocument(fullDocument, 6000);

            String userPrompt = String.format(CONTEXT_USER_PROMPT_TEMPLATE,
                    truncatedDoc, chunkContent);

            List<LLMClient.Message> messages = new ArrayList<>();
            messages.add(LLMClient.Message.builder()
                    .role("user")
                    .content(userPrompt)
                    .build());

            LLMClient.ChatRequest request = LLMClient.ChatRequest.builder()
                    .systemPrompt(CONTEXT_SYSTEM_PROMPT)
                    .messages(messages)
                    .maxTokens(100)
                    .temperature(0.2) // 低温度确保输出稳定
                    .build();

            LLMClient client = llmRouter.getClient(null);
            String context = client.chat(request);

            if (context != null && !context.trim().isEmpty()) {
                log.debug("生成上下文: {} -> {}",
                        truncate(chunkContent, 50), context.trim());
                return context.trim();
            }
        } catch (Exception e) {
            log.warn("上下文生成失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 批量为 chunks 生成上下文前缀
     * 
     * @param fullDocument 完整文档文本
     * @param chunks       chunk 列表
     * @return 更新了 contextPrefix 的 chunk 列表
     */
    public List<ChunkDto> enrichChunks(String fullDocument, List<ChunkDto> chunks) {
        if (!appProperties.getRag().isContextualRetrievalEnabled()) {
            log.debug("上下文增强已禁用，跳过处理");
            return chunks;
        }

        log.info("开始上下文增强处理: {} 个 chunks", chunks.size());
        int successCount = 0;

        for (ChunkDto chunk : chunks) {
            try {
                String context = enrichChunk(fullDocument, chunk.getContent());
                if (context != null) {
                    chunk.setContextPrefix(context);
                    successCount++;
                }

                // 添加小延迟避免 API 限流
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("上下文增强被中断");
                break;
            }
        }

        log.info("上下文增强完成: {}/{} 个 chunks 成功", successCount, chunks.size());
        return chunks;
    }

    /**
     * 获取用于 embedding 的增强内容
     * 如果有上下文前缀，则合并；否则返回原内容
     */
    public String getEnrichedContent(ChunkDto chunk) {
        if (chunk.getContextPrefix() != null && !chunk.getContextPrefix().isEmpty()) {
            return chunk.getContextPrefix() + "\n" + chunk.getContent();
        }
        return chunk.getContent();
    }

    /**
     * 截断文档以适应 LLM token 限制
     */
    private String truncateDocument(String document, int maxChars) {
        if (document == null) {
            return "";
        }
        if (document.length() <= maxChars) {
            return document;
        }
        // 保留开头和结尾部分
        int headSize = maxChars * 2 / 3;
        int tailSize = maxChars - headSize - 20;
        return document.substring(0, headSize) +
                "\n\n[... 中间内容已省略 ...]\n\n" +
                document.substring(document.length() - tailSize);
    }

    private String truncate(String text, int maxLength) {
        if (text == null)
            return "";
        if (text.length() <= maxLength)
            return text;
        return text.substring(0, maxLength) + "...";
    }
}
