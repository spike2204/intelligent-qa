package com.example.qa.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 聊天响应块DTO - 用于流式输出
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatChunkDto {
    private String content; // 文本片段
    private boolean complete; // 是否结束
    private List<CitationDto> citations; // 引用来源(最后返回)
    private String error; // 错误信息
    private String warning; // 警告信息(如切换模型)
}
