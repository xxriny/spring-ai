package dev.springrag.dto;

public record SearchHitDto(String chunkId, String documentId, String title, String url, String section,
                           String category, String text, Object page, int rank, Double vectorScore, Double rerankerScore) {}
