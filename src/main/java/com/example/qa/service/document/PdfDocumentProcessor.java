package com.example.qa.service.document;

import com.example.qa.exception.DocumentProcessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF文档处理器 - 将PDF转换为结构化Markdown
 */
@Slf4j
@Component
public class PdfDocumentProcessor implements DocumentProcessor {

    // 标题检测模式
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(\\d+\\.\\s+.+|第[一二三四五六七八九十百]+[章节条款]\\s*.+|[一二三四五六七八九十]+[、.]\\s*.+)$",
            Pattern.MULTILINE);

    // 子标题模式 (如 1.1, 1.2.3)
    private static final Pattern SUB_HEADING_PATTERN = Pattern.compile(
            "^(\\d+\\.\\d+\\.?\\s+.+|\\d+\\.\\d+\\.\\d+\\.?\\s+.+)$",
            Pattern.MULTILINE);

    @Override
    public boolean supports(String fileType) {
        return "pdf".equalsIgnoreCase(fileType);
    }

    @Override
    public String extractText(InputStream inputStream, String filename) {
        PDDocument document = null;
        try {
            document = PDDocument.load(inputStream);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            String rawText = stripper.getText(document);

            // 转换为Markdown格式
            String markdownText = convertToMarkdown(rawText);

            log.info("PDF解析完成: {}, 页数: {}, 原始字符: {}, Markdown字符: {}",
                    filename, document.getNumberOfPages(),
                    rawText.length(), markdownText.length());

            return markdownText;
        } catch (IOException e) {
            log.error("PDF解析失败: {}", filename, e);
            throw new DocumentProcessException("PDF文件解析失败: " + e.getMessage(), e);
        } finally {
            if (document != null) {
                try {
                    document.close();
                } catch (IOException e) {
                    log.warn("关闭PDF文档失败", e);
                }
            }
        }
    }

    /**
     * 将纯文本转换为Markdown格式，识别标题结构
     */
    private String convertToMarkdown(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        String[] lines = text.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                result.append("\n");
                continue;
            }

            // 过滤纯页码 (如 "1", " 45 ", "- 1 -")
            if (trimmed.matches("^-?\\s*\\d+\\s*-?$")) {
                continue;
            }

            // 检测一级标题 (如 "3. 进阶要求")
            Matcher headingMatcher = HEADING_PATTERN.matcher(trimmed);
            if (headingMatcher.matches()) {
                result.append("\n\n## ").append(trimmed).append("\n\n");
                continue;
            }

            // 检测二级标题 (如 "3.1 处理策略")
            Matcher subHeadingMatcher = SUB_HEADING_PATTERN.matcher(trimmed);
            if (subHeadingMatcher.matches()) {
                result.append("\n### ").append(trimmed).append("\n\n");
                continue;
            }

            // 检测列表项 (添加换行以形成宽松列表，提升可读性)
            if (trimmed.startsWith("●") || trimmed.startsWith("•") ||
                    trimmed.startsWith("-") || trimmed.startsWith("○")) {
                result.append("\n- ").append(trimmed.substring(1).trim()).append("\n");
                continue;
            }

            // 普通段落 (与前文隔开，避免紧凑)
            // 如果只有一行，可能只是普通断行；这里简单处理为追加，依靠前端 breaks: true
            result.append(trimmed).append("\n");
        }

        return result.toString();
    }
}
