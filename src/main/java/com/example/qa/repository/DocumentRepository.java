package com.example.qa.repository;

import com.example.qa.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, String> {
    List<Document> findByStatusOrderByCreatedAtDesc(Document.DocumentStatus status);
    List<Document> findAllByOrderByCreatedAtDesc();
}
