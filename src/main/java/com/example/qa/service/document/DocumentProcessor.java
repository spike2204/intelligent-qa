package com.example.qa.service.document;

import com.example.qa.dto.ChunkDto;
import java.io.InputStream;
import java.util.List;

/**
 * 文档处理器接口
 */
public interface DocumentProcessor {
    
    /**
     * 判断是否支持该文件类型
     */
    boolean supports(String fileType);
    
    /**
     * 从文档中提取文本内容
     */
    String extractText(InputStream inputStream, String filename);
}
