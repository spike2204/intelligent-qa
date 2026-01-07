package com.example.qa.controller;

import com.example.qa.dto.ChatChunkDto;
import com.example.qa.dto.ChatRequestDto;
import com.example.qa.entity.ChatSession;
import com.example.qa.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
import java.util.Map;

/**
 * 聊天控制器 - 支持流式输出
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatController {

    private final ChatService chatService;

    /**
     * 创建聊天会话
     */
    @PostMapping("/sessions")
    public ResponseEntity<ChatSession> createSession(@RequestBody Map<String, String> request) {
        String documentId = request.get("documentId");
        ChatSession session = chatService.createSession(documentId);
        return ResponseEntity.ok(session);
    }

    /**
     * 流式问答 (Server-Sent Events)
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatChunkDto>> streamChat(
            @RequestParam String query,
            @RequestParam String sessionId,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String model) {

        return chatService.streamAnswer(query, sessionId, documentId, model)
                .map(chunk -> ServerSentEvent.<ChatChunkDto>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * 同步问答 (非流式)
     */
    @PostMapping
    public ResponseEntity<ChatChunkDto> chat(@Valid @RequestBody ChatRequestDto request) {
        ChatChunkDto response = chatService.answer(
                request.getQuery(),
                request.getSessionId(),
                request.getDocumentId(),
                request.getModelType());
        return ResponseEntity.ok(response);
    }
}
