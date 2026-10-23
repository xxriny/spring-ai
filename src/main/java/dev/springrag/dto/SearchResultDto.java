package dev.springrag.dto;

import java.util.List;

public record SearchResultDto(List<SearchHitDto> vectorCandidates, List<SearchHitDto> bm25Candidates,
                              List<SearchHitDto> candidates, List<SearchHitDto> reranked,
                              List<SearchHitDto> passages) {
    public SearchResultDto(List<SearchHitDto> candidates, List<SearchHitDto> reranked) {
        this(candidates, List.of(), candidates, reranked, List.of());
    }
}
