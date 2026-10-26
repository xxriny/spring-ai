package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 검색된 청크 또는 확장된 passage의 출처와 점수를 나타낸다.
 * @param chunkId 대표 청크 ID
 * @param documentId 소속 문서 ID
 * @param title 문서 제목
 * @param url 출처 URL 또는 업로드 식별자
 * @param section HTML 절 제목
 * @param category 문서 카테고리
 * @param text 청크 또는 passage 본문
 * @param page PDF 페이지 번호, 그 외에는 null
 * @param rank 해당 단계 순위
 * @param vectorScore 벡터 검색 점수, 없는 경우 null
 * @param rerankerScore 재정렬 점수, 없는 경우 null
 * @param sourceChunkIds passage에 포함된 청크 ID 목록
 */
@Schema(description = "검색 청크 또는 최종 passage")
public record SearchHitDto(
    @Schema(description = "대표 청크 ID", example = "e64ae5d4-1f6e-4ef3-bbe4-0c5ec7e21692") String chunkId,
    @Schema(description = "소속 문서 ID", example = "123e4567-e89b-12d3-a456-426614174000") String documentId,
    @Schema(description = "문서 제목", example = "Spring AI ETL") String title,
    @Schema(description = "출처 URL 또는 upload", example = "https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html") String url,
    @Schema(description = "HTML 절 제목", example = "Extract") String section,
    @Schema(description = "문서 카테고리", example = "Spring AI docs") String category,
    @Schema(description = "청크 또는 passage 본문", example = "ETL 파이프라인은 문서를 추출하고 변환합니다.") String text,
    @Schema(description = "PDF 페이지 번호, 해당하지 않으면 null", example = "2") Object page,
    @Schema(description = "해당 단계 순위", example = "1") int rank,
    @Schema(description = "벡터 검색 점수, 해당하지 않으면 null", example = "0.82") Double vectorScore,
    @Schema(description = "Qwen 재정렬 점수, 해당하지 않으면 null", example = "0.91") Double rerankerScore,
    @Schema(description = "최종 passage에 포함된 청크 ID") List<String> sourceChunkIds) {
    /**
     * 확장하지 않은 단일 청크를 출처로 갖는 검색 결과를 만든다.
     *
     * @param chunkId 대표 청크 ID
     * @param documentId 소속 문서 ID
     * @param title 문서 제목
     * @param url 출처 URL 또는 업로드 식별자
     * @param section HTML 절 제목
     * @param category 문서 카테고리
     * @param text 청크 본문
     * @param page PDF 페이지 번호
     * @param rank 해당 단계 순위
     * @param vectorScore 벡터 검색 점수
     * @param rerankerScore 재정렬 점수
     */
    public SearchHitDto(String chunkId, String documentId, String title, String url, String section,
                        String category, String text, Object page, int rank, Double vectorScore, Double rerankerScore) {
        this(chunkId, documentId, title, url, section, category, text, page, rank, vectorScore,
            rerankerScore, List.of(chunkId));
    }
}
