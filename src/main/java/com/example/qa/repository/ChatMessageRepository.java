package com.example.qa.repository;

import com.example.qa.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    List<ChatMessage> findBySessionIdOrderByCreatedAtDesc(String sessionId);
    void deleteBySessionId(String sessionId);
    int countBySessionId(String sessionId);
}
