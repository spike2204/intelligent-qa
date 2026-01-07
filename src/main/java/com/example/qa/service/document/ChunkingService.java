package com.example.qa.service.document;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.ChunkDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档切片服务 - 支持递归字符切片和基于标题的切片
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final AppProperties appProperties;

    // 章节标题正则(支持Markdown标题和数字编号)
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,6}\\s+.+|\\d+\\.\\d*\\s+.+|第[一二三四五六七八九十百]+[章节条款]\\s*.*)$",
            Pattern.MULTILINE);

    /**
     * 将文本切分为多个块
     */
    public List<ChunkDto> chunkText(String text, String documentId) {
        List<ChunkDto> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int chunkSize = appProperties.getChunking().getChunkSize();
        int chunkOverlap = appProperties.getChunking().getChunkOverlap();
        int minChunkSize = appProperties.getChunking().getMinChunkSize();

        // 先按章节标题拆分
        List<TextSection> sections = splitByHeadings(text);

        int chunkIndex = 0;
        for (TextSection section : sections) {
            // 对每个章节进行递归字符切片
            List<String> sectionChunks = recursiveCharacterSplit(
                    section.content, chunkSize, chunkOverlap, minChunkSize);

            for (String chunkContent : sectionChunks) {
                ChunkDto chunk = new ChunkDto();
                chunk.setId(UUID.randomUUID().toString());
                chunk.setDocumentId(documentId);
                chunk.setContent(chunkContent);
                chunk.setChunkIndex(chunkIndex++);
                chunk.setHeading(section.heading);
                chunk.setHierarchy(section.hierarchy);
                chunk.setTokenCount(estimateTokenCount(chunkContent));
                chunks.add(chunk);
            }
        }

        log.info("文档切片完成: documentId={}, 总块数={}", documentId, chunks.size());
        return chunks;
    }

    /**
     * 按标题拆分文本，并保留层级结构
     */
    private List<TextSection> splitByHeadings(String text) {
        List<TextSection> sections = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(text);

        int lastEnd = 0;
        String currentHeading = null;

        // 简单的栈来维护层级 (仅示例，实际可能需要更复杂的逻辑)
        // 这里我们简单地将所有前面的标题作为上下文
        // 更好的做法是解析 "1.", "1.1" 等数字来维护树状结构
        List<String> headingStack = new ArrayList<>();

        while (matcher.find()) {
            String heading = matcher.group().trim();

            // 保存前一个section
            if (lastEnd < matcher.start()) {
                String content = text.substring(lastEnd, matcher.start()).trim();
                if (!content.isEmpty()) {
                    String hierarchy = String.join(" > ", headingStack);
                    if (currentHeading != null && !hierarchy.endsWith(currentHeading)) { // 避免重复
                        if (hierarchy.isEmpty())
                            hierarchy = currentHeading;
                        else
                            hierarchy += " > " + currentHeading;
                    }
                    // 如果栈为空，hierarchy就是currentHeading (可能为null)
                    if (hierarchy.isEmpty())
                        hierarchy = currentHeading;

                    sections.add(new TextSection(currentHeading, hierarchy, content));
                }
            }

            // 更新层级栈 (简单逻辑: 遇到新标题，尝试判断层级)
            // 这是一个简化实现。真实场景需要根据 # 数量或 1.1.1 深度来 push/pop
            updateHeadingStack(headingStack, heading);

            currentHeading = heading;
            lastEnd = matcher.end();
        }

        // 处理最后一个section
        if (lastEnd < text.length()) {
            String content = text.substring(lastEnd).trim();
            if (!content.isEmpty()) {
                String hierarchy = String.join(" > ", headingStack);
                // 确保当前标题在层级中
                if (currentHeading != null) {
                    if (hierarchy.isEmpty())
                        hierarchy = currentHeading;
                    else if (!hierarchy.contains(currentHeading))
                        hierarchy += " > " + currentHeading;
                }
                sections.add(new TextSection(currentHeading, hierarchy, content));
            }
        }

        if (sections.isEmpty() && !text.trim().isEmpty()) {
            sections.add(new TextSection(null, null, text.trim()));
        }

        return sections;
    }

    private void updateHeadingStack(List<String> stack, String newHeading) {
        // 简单启发式:
        // 1. 如果是 Markdown 标题 (#, ##), 根据数量判断层级
        // 2. 如果是数字 (1., 1.1), 根据点号数量判断

        int level = getLevel(newHeading);

        // 如果栈的大小 >= 当前层级，说明要回退 (pop)
        while (stack.size() >= level) {
            if (!stack.isEmpty()) {
                stack.remove(stack.size() - 1);
            } else {
                break;
            }
        }
        stack.add(newHeading);
    }

    private int getLevel(String heading) {
        if (heading.startsWith("#")) {
            return heading.indexOf(" "); // # 数量即为层级 (1-6)
        }
        // "1. 基础" -> 1, "1.1 音量" -> 2
        // 简单计数 '.' 的数量 + 1
        if (Character.isDigit(heading.charAt(0))) {
            long dots = heading.chars().filter(ch -> ch == '.').count();
            // "1." -> dots=1 -> level 1? Usually "1." is level 1. "1.1" dots=1.
            // Let's refine: "1." end with dot vs "1.1"
            return (int) dots + 1; // 粗略估计
        }
        return 1; // 默认层级1
    }

    /**
     * 递归字符切片
     */
    private List<String> recursiveCharacterSplit(String text, int chunkSize,
            int chunkOverlap, int minChunkSize) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            if (text.length() >= minChunkSize) {
                chunks.add(text);
            }
            return chunks;
        }

        // 分隔符优先级: 段落 > 句子 > 子句 > 空格
        String[] separators = { "\n\n", "\n", "。", "！", "？", ".", "!", "?", "；", ";", "，", ",", " " };

        for (String separator : separators) {
            if (text.contains(separator)) {
                chunks = splitBySeparator(text, separator, chunkSize, chunkOverlap, minChunkSize);
                if (!chunks.isEmpty()) {
                    return chunks;
                }
            }
        }

        // 如果没有合适的分隔符，强制按长度切分
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunk = text.substring(start, end).trim();
            if (chunk.length() >= minChunkSize) {
                chunks.add(chunk);
            }
            start = end - chunkOverlap;
            if (start >= text.length())
                break;
        }

        return chunks;
    }

    private List<String> splitBySeparator(String text, String separator,
            int chunkSize, int chunkOverlap, int minChunkSize) {
        List<String> chunks = new ArrayList<>();
        String[] parts = text.split(Pattern.quote(separator));

        StringBuilder currentChunk = new StringBuilder();

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
                continue;

            if (currentChunk.length() + trimmed.length() + separator.length() <= chunkSize) {
                if (currentChunk.length() > 0) {
                    currentChunk.append(separator);
                }
                currentChunk.append(trimmed);
            } else {
                // 保存当前块
                if (currentChunk.length() >= minChunkSize) {
                    chunks.add(currentChunk.toString());
                }

                // 处理重叠: 取上一个块的末尾部分
                if (chunkOverlap > 0 && currentChunk.length() > chunkOverlap) {
                    String overlap = currentChunk.substring(
                            currentChunk.length() - chunkOverlap);
                    currentChunk = new StringBuilder(overlap + separator + trimmed);
                } else {
                    currentChunk = new StringBuilder(trimmed);
                }
            }
        }

        // 保存最后一个块
        if (currentChunk.length() >= minChunkSize) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 估算token数量(简单估算: 中文1字≈1token，英文4字符≈1token)
     */
    private int estimateTokenCount(String text) {
        int chineseCount = 0;
        int otherCount = 0;

        for (char c : text.toCharArray()) {
            if (Character.toString(c).matches("[\\u4e00-\\u9fa5]")) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }

        return chineseCount + (otherCount / 4);
    }

    private static class TextSection {
        String heading;
        String hierarchy;
        String content;

        TextSection(String heading, String hierarchy, String content) {
            this.heading = heading;
            this.hierarchy = hierarchy;
            this.content = content;
        }
    }
}
