package com.example.qa.exception;

/**
 * LLM调用异常
 */
public class LLMException extends RuntimeException {
    
    private final LLMErrorType errorType;
    
    public LLMException(LLMErrorType errorType, String message) {
        super(message);
        this.errorType = errorType;
    }
    
    public LLMException(LLMErrorType errorType, String message, Throwable cause) {
        super(message, cause);
        this.errorType = errorType;
    }
    
    public LLMErrorType getErrorType() {
        return errorType;
    }
    
    public enum LLMErrorType {
        RATE_LIMIT,      // 限流
        TIMEOUT,         // 超时
        AUTH_ERROR,      // 认证失败
        NETWORK_ERROR,   // 网络异常
        INVALID_REQUEST, // 请求无效
        SERVICE_ERROR    // 服务异常
    }
}
