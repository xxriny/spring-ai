package dev.springrag.dto;

import java.util.List;

public record SearchResultDto(List<SearchHitDto> candidates, List<SearchHitDto> reranked) {}
