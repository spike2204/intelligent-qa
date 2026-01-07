package com.example.qa.dto;

import javax.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求DTO
 */
@Data
public class ChatRequestDto {
    @NotBlank(message = "问题不能为空")
    private String query;

    private String sessionId;

    private String documentId;

    private String modelType;
}
