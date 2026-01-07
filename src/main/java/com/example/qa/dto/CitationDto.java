package com.example.qa.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 引用来源DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDto {
    private String chunkId;
    private String documentName;
    private Integer pageNumber;
    private String excerpt;
    private Double score;
}
