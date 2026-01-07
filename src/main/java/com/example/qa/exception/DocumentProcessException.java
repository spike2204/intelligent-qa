package com.example.qa.exception;

/**
 * 文档处理异常
 */
public class DocumentProcessException extends RuntimeException {
    
    public DocumentProcessException(String message) {
        super(message);
    }
    
    public DocumentProcessException(String message, Throwable cause) {
        super(message, cause);
    }
}
