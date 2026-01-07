package com.example.qa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 智能问答系统主启动类
 * 
 * 功能特性:
 * - PDF/Markdown文档上传和解析
 * - 基于RAG的智能问答
 * - 流式响应输出
 * - 答案来源引用
 */
@SpringBootApplication
@EnableAsync
public class IntelligentQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelligentQaApplication.class, args);
    }
}
