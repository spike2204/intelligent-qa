package com.example.qa.controller;

import com.example.qa.dto.DocumentDto;
import com.example.qa.dto.ChunkDto;
import com.example.qa.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理控制器
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     */
    @PostMapping
    public ResponseEntity<DocumentDto> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "skipEnrichment", defaultValue = "false") boolean skipEnrichment) {
        DocumentDto document = documentService.uploadDocument(file, skipEnrichment);
        return ResponseEntity.ok(document);
    }

    /**
     * 获取文档列表
     */
    @GetMapping
    public ResponseEntity<List<DocumentDto>> list() {
        return ResponseEntity.ok(documentService.listDocuments());
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentDto> get(@PathVariable String id) {
        return ResponseEntity.ok(documentService.getDocument(id));
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        documentService.deleteDocument(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 获取文档切片列表
     */
    @GetMapping("/{id}/chunks")
    public ResponseEntity<List<ChunkDto>> getChunks(@PathVariable String id) {
        return ResponseEntity.ok(documentService.getDocumentChunks(id));
    }
}
