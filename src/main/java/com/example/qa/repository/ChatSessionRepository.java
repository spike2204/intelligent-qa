package com.example.qa.repository;

import com.example.qa.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    // 使用 LIKE 查询支持在逗号分隔的 documentIds 中查找
    @org.springframework.data.jpa.repository.Query("SELECT s FROM ChatSession s WHERE s.documentIds LIKE %:documentId% ORDER BY s.createdAt DESC")
    List<ChatSession> findByDocumentIdInDocumentIds(
            @org.springframework.data.repository.query.Param("documentId") String documentId);
}
