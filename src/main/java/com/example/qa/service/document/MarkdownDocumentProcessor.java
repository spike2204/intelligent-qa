package com.example.qa.service.document;

import com.example.qa.exception.DocumentProcessException;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Markdown文档处理器 - 使用Flexmark
 */
@Slf4j
@Component
public class MarkdownDocumentProcessor implements DocumentProcessor {

    private final Parser parser;

    public MarkdownDocumentProcessor() {
        this.parser = Parser.builder().build();
    }

    @Override
    public boolean supports(String fileType) {
        return "md".equalsIgnoreCase(fileType) || "markdown".equalsIgnoreCase(fileType)
                || "txt".equalsIgnoreCase(fileType);
    }

    @Override
    public String extractText(InputStream inputStream, String filename) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {

            String markdown = reader.lines().collect(Collectors.joining("\n"));

            // 解析Markdown并提取纯文本(去除格式标记)
            Node document = parser.parse(markdown);
            String text = extractPlainText(document);

            log.info("Markdown解析完成: {}, 字符数: {}", filename, text.length());
            return text;

        } catch (IOException e) {
            log.error("Markdown解析失败: {}", filename, e);
            throw new DocumentProcessException("Markdown文件读取失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从AST中提取纯文本，保留文档结构
     */
    private String extractPlainText(Node document) {
        StringBuilder sb = new StringBuilder();
        extractTextFromNode(document, sb);
        return sb.toString();
    }

    private void extractTextFromNode(Node node, StringBuilder sb) {
        // 遍历所有子节点
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof com.vladsch.flexmark.ast.Text) {
                sb.append(child.getChars());
            } else if (child instanceof com.vladsch.flexmark.ast.SoftLineBreak
                    || child instanceof com.vladsch.flexmark.ast.HardLineBreak) {
                sb.append("\n");
            } else if (child instanceof com.vladsch.flexmark.ast.Paragraph) {
                extractTextFromNode(child, sb);
                sb.append("\n\n");
            } else if (child instanceof com.vladsch.flexmark.ast.Heading) {
                extractTextFromNode(child, sb);
                sb.append("\n");
            } else {
                extractTextFromNode(child, sb);
            }
            child = child.getNext();
        }
    }
}
