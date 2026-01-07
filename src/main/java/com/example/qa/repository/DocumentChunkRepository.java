package com.example.qa.repository;

import com.example.qa.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, String> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndex(String documentId);

    void deleteByDocumentId(String documentId);

    @Query("SELECT DISTINCT c.hierarchy FROM DocumentChunk c WHERE c.documentId = :documentId AND c.hierarchy IS NOT NULL")
    List<String> findDistinctHierarchyByDocumentId(@Param("documentId") String documentId);

    int countByDocumentId(String documentId);
}
