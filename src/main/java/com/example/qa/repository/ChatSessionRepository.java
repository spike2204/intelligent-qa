package com.example.qa.repository;

import com.example.qa.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByDocumentIdOrderByCreatedAtDesc(String documentId);
}
