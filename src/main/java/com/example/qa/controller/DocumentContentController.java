package com.example.qa.controller;

import com.example.qa.dto.DocumentDto;
import com.example.qa.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Slf4j
public class DocumentContentController {

    private final DocumentService documentService;

    @GetMapping("/{id}/content")
    public ResponseEntity<Map<String, String>> getDocumentContent(@PathVariable String id) {
        DocumentDto document = documentService.getDocument(id);
        if (document == null) {
            return ResponseEntity.notFound().build();
        }

        // Ensure we return empty string if content is null
        String content = document.getFullText();
        if (content == null) {
            content = "";
        }

        return ResponseEntity.ok(java.util.Collections.singletonMap("content", content));
    }
}
