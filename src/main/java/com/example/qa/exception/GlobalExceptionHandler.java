package com.example.qa.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    
    @ExceptionHandler(DocumentProcessException.class)
    public ResponseEntity<ErrorResponse> handleDocumentProcess(DocumentProcessException e) {
        log.error("文档处理异常: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("DOCUMENT_PROCESS_ERROR", e.getMessage()));
    }
    
    @ExceptionHandler(LLMException.class)
    public ResponseEntity<ErrorResponse> handleLLM(LLMException e) {
        log.error("LLM调用异常: type={}, message={}", e.getErrorType(), e.getMessage());
        
        HttpStatus status;
        switch (e.getErrorType()) {
            case RATE_LIMIT:
                status = HttpStatus.TOO_MANY_REQUESTS;
                break;
            case TIMEOUT:
            case NETWORK_ERROR:
            case SERVICE_ERROR:
                status = HttpStatus.SERVICE_UNAVAILABLE;
                break;
            case AUTH_ERROR:
                status = HttpStatus.UNAUTHORIZED;
                break;
            case INVALID_REQUEST:
                status = HttpStatus.BAD_REQUEST;
                break;
            default:
                status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        
        return ResponseEntity.status(status)
                .body(new ErrorResponse("LLM_ERROR", e.getMessage()));
    }
    
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件大小超过限制"));
    }
    
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_ARGUMENT", e.getMessage()));
    }
    
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception e) {
        log.error("未处理的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "服务器内部错误"));
    }
    
    @Data
    @AllArgsConstructor
    public static class ErrorResponse {
        private String code;
        private String message;
    }
}
