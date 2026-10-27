package dev.springrag.retrieval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 검색 단계별 후보와 최종 근거 passage를 함께 반환한다.
 * @param vectorCandidates 벡터 검색 후보
 * @param bm25Candidates BM25 검색 후보
 * @param candidates RRF 결합 후보
 * @param reranked 리랭커 정렬 결과
 * @param passages 앞뒤 청크를 확장한 최종 근거
 */
@Schema(description = "하이브리드 검색 결과")
public record SearchResultDto(
    @Schema(description = "벡터 검색 후보, 최대 10개") List<SearchHitDto> vectorCandidates,
    @Schema(description = "BM25 검색 후보, 최대 10개") List<SearchHitDto> bm25Candidates,
    @Schema(description = "RRF 결합 후보, 최대 10개") List<SearchHitDto> candidates,
    @Schema(description = "Qwen 리랭커 정렬 결과") List<SearchHitDto> reranked,
    @Schema(description = "확장·압축한 최종 근거, 최대 3개") List<SearchHitDto> passages) {
    /**
     * 기존 후보·재정렬 응답 형식을 유지하는 간편 생성자다.
     *
     * @param candidates 검색 후보
     * @param reranked 재정렬 결과
     */
    public SearchResultDto(List<SearchHitDto> candidates, List<SearchHitDto> reranked) {
        this(candidates, List.of(), candidates, reranked, List.of());
    }
}
