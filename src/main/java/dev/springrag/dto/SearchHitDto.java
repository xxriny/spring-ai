package dev.springrag.dto;

import java.util.List;

public record SearchHitDto(String chunkId, String documentId, String title, String url, String section,
                           String category, String text, Object page, int rank, Double vectorScore,
                           Double rerankerScore, List<String> sourceChunkIds) {
    public SearchHitDto(String chunkId, String documentId, String title, String url, String section,
                        String category, String text, Object page, int rank, Double vectorScore, Double rerankerScore) {
        this(chunkId, documentId, title, url, section, category, text, page, rank, vectorScore,
            rerankerScore, List.of(chunkId));
    }
}
