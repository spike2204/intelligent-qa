package com.example.qa.service;

import com.example.qa.config.AppProperties;
import com.example.qa.dto.ChunkDto;
import com.example.qa.dto.DocumentDto;
import com.example.qa.entity.Document;
import com.example.qa.entity.DocumentChunk;
import com.example.qa.exception.DocumentProcessException;
import com.example.qa.repository.DocumentChunkRepository;
import com.example.qa.repository.DocumentRepository;
import com.example.qa.service.document.ChunkingService;
import com.example.qa.service.document.ContextualEnrichmentService;
import com.example.qa.service.document.DocumentProcessor;
import com.example.qa.service.embedding.EmbeddingService;
import com.example.qa.service.retrieval.BM25Service;
import com.example.qa.service.vector.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档服务 - 负责文档上传、解析、切片和向量化
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final List<DocumentProcessor> processors;
    private final ChunkingService chunkingService;
    private final ContextualEnrichmentService contextualEnrichmentService;
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final AppProperties appProperties;
    private final BM25Service bm25Service;

    /**
     * 上传并处理文档
     */
    @Transactional
    public DocumentDto uploadDocument(MultipartFile file, boolean skipEnrichment) {
        validateFile(file);
        System.out.println(file.getOriginalFilename() + "校验成功");

        String documentId = UUID.randomUUID().toString();
        String filename = file.getOriginalFilename();
        String fileType = getFileType(filename);

        // 保存文件
        String filePath = saveFile(file, documentId);

        // 创建文档记录
        Document document = new Document();
        document.setId(documentId);
        document.setFilename(filename);
        document.setFileType(fileType);
        document.setFileSize(file.getSize());
        document.setFilePath(filePath);
        document.setStatus(Document.DocumentStatus.PROCESSING);
        documentRepository.save(document);

        // 异步处理文档
        processDocumentAsync(documentId, filePath, fileType, filename, skipEnrichment);

        return toDto(document);
    }

    // Overload for backward compatibility if needed, though controller will update
    @Transactional
    public DocumentDto uploadDocument(MultipartFile file) {
        return uploadDocument(file, false);
    }

    /**
     * 异步处理文档: 解析 -> 切片 -> 向量化
     */
    @Async
    public void processDocumentAsync(String documentId, String filePath,
            String fileType, String filename, boolean skipEnrichment) {
        try {
            // 1. 解析文档
            DocumentProcessor processor = getProcessor(fileType);
            try (InputStream inputStream = Files.newInputStream(Paths.get(filePath))) {
                String text = processor.extractText(inputStream, filename);

                // 2. 切片
                List<ChunkDto> chunks = chunkingService.chunkText(text, documentId);

                // 2.5 【新增】上下文增强 (Contextual Retrieval)
                if (!skipEnrichment && appProperties.getRag().isContextualRetrievalEnabled()) {
                    log.info("开始上下文增强处理...");
                    chunks = contextualEnrichmentService.enrichChunks(text, chunks);
                } else {
                    if (skipEnrichment) {
                        log.info("跳过上下文增强处理 (用户请求)");
                    } else {
                        log.info("跳过上下文增强处理 (配置禁用)");
                    }
                }

                // 使用 final 变量以便在 lambda 中使用
                final List<ChunkDto> finalChunks = chunks;
                final int chunkCount = finalChunks.size();

                // 3. 保存切片到数据库
                List<DocumentChunk> entities = saveChunks(finalChunks, filename);

                // 4. 向量化并存入向量库
                vectorizeAndStore(finalChunks, filename);

                // 5. 【新增】建立BM25索引
                indexBM25(documentId, finalChunks, filename);

                // 6. 更新文档状态
                final String fullText = text; // capture for lambda

                documentRepository.findById(documentId).ifPresent(doc -> {
                    doc.setStatus(Document.DocumentStatus.READY);
                    doc.setChunkCount(chunkCount);
                    // 始终保存完整文本，用于前端全文展示和定位
                    doc.setFullText(fullText);
                    log.info("文档处理完成: 保存完整文本 (len={})", fullText.length());
                    documentRepository.save(doc);
                });

                log.info("文档处理完成: id={}, filename={}, chunks={}",
                        documentId, filename, chunkCount);
            }

        } catch (Exception e) {
            log.error("文档处理失败: id={}", documentId, e);
            documentRepository.findById(documentId).ifPresent(doc -> {
                doc.setStatus(Document.DocumentStatus.FAILED);
                documentRepository.save(doc);
            });
        }
    }

    private List<DocumentChunk> saveChunks(List<ChunkDto> chunks, String filename) {
        return chunks.stream().map(dto -> {
            DocumentChunk entity = new DocumentChunk();
            entity.setId(dto.getId());
            entity.setDocumentId(dto.getDocumentId());
            entity.setContent(dto.getContent());
            entity.setChunkIndex(dto.getChunkIndex());
            entity.setHeading(dto.getHeading());
            entity.setHierarchy(dto.getHierarchy());
            entity.setStartPage(dto.getStartPage());
            entity.setEndPage(dto.getEndPage());
            entity.setTokenCount(dto.getTokenCount());
            entity.setContextPrefix(dto.getContextPrefix());
            return chunkRepository.save(entity);
        }).collect(Collectors.toList());
    }

    private void vectorizeAndStore(List<ChunkDto> chunks, String filename) {
        // 使用增强后的内容进行 embedding（如果有上下文前缀）
        List<String> contents = chunks.stream()
                .map(chunk -> contextualEnrichmentService.getEnrichedContent(chunk))
                .collect(Collectors.toList());

        List<float[]> embeddings = embeddingService.embedBatch(contents);

        List<VectorStore.VectorDocument> vectorDocs = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDto chunk = chunks.get(i);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("filename", filename);
            metadata.put("chunkIndex", chunk.getChunkIndex());
            metadata.put("heading", chunk.getHeading() != null ? chunk.getHeading() : "");
            metadata.put("hierarchy", chunk.getHierarchy() != null ? chunk.getHierarchy() : "");
            metadata.put("startPage", chunk.getStartPage() != null ? chunk.getStartPage() : 0);

            vectorDocs.add(VectorStore.VectorDocument.builder()
                    .id(chunk.getId())
                    .documentId(chunk.getDocumentId())
                    .content(chunk.getContent())
                    .embedding(embeddings.get(i))
                    .metadata(metadata)
                    .build());
        }

        vectorStore.insert(vectorDocs);
    }

    /**
     * 建立BM25关键词索引
     */
    private void indexBM25(String documentId, List<ChunkDto> chunks, String filename) {
        List<BM25Service.ChunkData> chunkDataList = chunks.stream()
                .map(chunk -> {
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("filename", filename);
                    metadata.put("chunkIndex", chunk.getChunkIndex());
                    metadata.put("heading", chunk.getHeading());
                    metadata.put("hierarchy", chunk.getHierarchy());
                    metadata.put("startPage", chunk.getStartPage());

                    // 使用增强后的内容进行 BM25 索引
                    String contentToIndex = contextualEnrichmentService.getEnrichedContent(chunk);

                    return BM25Service.ChunkData.builder()
                            .id(chunk.getId())
                            .content(contentToIndex)
                            .metadata(metadata)
                            .build();
                })
                .collect(Collectors.toList());

        bm25Service.indexChunks(documentId, chunkDataList);
        log.info("BM25索引建立完成: documentId={}, chunks={}", documentId, chunks.size());
    }

    /**
     * 获取文档信息
     */
    public DocumentDto getDocument(String id) {
        return documentRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在: " + id));
    }

    /**
     * 获取文档列表
     */
    public List<DocumentDto> listDocuments() {
        return documentRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 删除文档
     */
    @Transactional
    public void deleteDocument(String id) {
        // 删除向量
        vectorStore.deleteByDocumentId(id);
        // 删除BM25索引
        bm25Service.deleteByDocumentId(id);
        // 删除切片
        chunkRepository.deleteByDocumentId(id);
        // 删除文档
        documentRepository.deleteById(id);
        log.info("文档已删除: id={}", id);
    }

    /**
     * 获取文档的切片列表
     */
    public List<ChunkDto> getDocumentChunks(String documentId) {
        return chunkRepository.findByDocumentIdOrderByChunkIndex(documentId).stream()
                .map(entity -> {
                    ChunkDto dto = new ChunkDto();
                    dto.setId(entity.getId());
                    dto.setDocumentId(entity.getDocumentId());
                    dto.setContent(entity.getContent());
                    dto.setChunkIndex(entity.getChunkIndex());
                    dto.setHeading(entity.getHeading());
                    dto.setStartPage(entity.getStartPage());
                    dto.setEndPage(entity.getEndPage());
                    dto.setTokenCount(entity.getTokenCount());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentProcessException("文件不能为空");
        }

        String filename = file.getOriginalFilename();
        String fileType = getFileType(filename);
        String allowedTypes = appProperties.getDocument().getAllowedTypes().toLowerCase();

        // Ensure robust check by splitting and trimming
        boolean isAllowed = Arrays.stream(allowedTypes.split(","))
                .map(String::trim)
                .anyMatch(type -> type.equalsIgnoreCase(fileType));

        if (!isAllowed) {
            throw new DocumentProcessException("不支持的文件类型: " + fileType + ". 允许的类型: " + allowedTypes);
        }

        if (file.getSize() > appProperties.getDocument().getMaxFileSize()) {
            throw new DocumentProcessException(
                    "文件大小超过限制. 当前: " + file.getSize() + ", 限制: " + appProperties.getDocument().getMaxFileSize());
        }
    }

    private String getFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();
    }

    private DocumentProcessor getProcessor(String fileType) {
        return processors.stream()
                .filter(p -> p.supports(fileType))
                .findFirst()
                .orElseThrow(() -> new DocumentProcessException("没有找到支持该类型的处理器: " + fileType));
    }

    private String saveFile(MultipartFile file, String documentId) {
        try {
            Path uploadDir = Paths.get(appProperties.getDocument().getStoragePath());
            if (!Files.exists(uploadDir)) {
                Files.createDirectories(uploadDir);
            }

            String filename = documentId + "_" + file.getOriginalFilename();
            Path filePath = uploadDir.resolve(filename).toAbsolutePath();
            file.transferTo(filePath.toFile());

            return filePath.toString();
        } catch (IOException e) {
            log.error("Failed to save file", e);
            throw new DocumentProcessException("文件保存失败: " + e.getMessage(), e);
        }
    }

    private DocumentDto toDto(Document entity) {
        return DocumentDto.builder()
                .id(entity.getId())
                .filename(entity.getFilename())
                .fileType(entity.getFileType())
                .fileSize(entity.getFileSize())
                .status(entity.getStatus())
                .chunkCount(entity.getChunkCount())
                .createdAt(entity.getCreatedAt())
                .fullText(entity.getFullText())
                .build();
    }
}
