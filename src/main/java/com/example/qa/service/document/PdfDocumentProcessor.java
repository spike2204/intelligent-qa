package com.example.qa.service.document;

import com.example.qa.exception.DocumentProcessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF文档处理器 - 使用Apache PDFBox 2.x (Java 8 兼容)
 */
@Slf4j
@Component
public class PdfDocumentProcessor implements DocumentProcessor {
    
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
            
            String text = stripper.getText(document);
            log.info("PDF解析完成: {}, 页数: {}, 字符数: {}", 
                    filename, document.getNumberOfPages(), text.length());
            
            return text;
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
}
