package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 생성 답변에 인용한 문서 발췌와 위치다.
 * @param title 문서 제목
 * @param url 출처 URL 또는 업로드 식별자
 * @param excerpt 근거 발췌
 * @param documentId 문서 ID
 * @param page PDF 페이지 번호, 그 외에는 null
 */
@Schema(description = "답변 근거 출처")
public record SourceDto(
    @Schema(description = "문서 제목", example = "Spring AI ETL") String title,
    @Schema(description = "출처 URL 또는 upload", example = "https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html") String url,
    @Schema(description = "근거 발췌", example = "ETL 파이프라인은 문서를 추출합니다.") String excerpt,
    @Schema(description = "문서 ID", example = "123e4567-e89b-12d3-a456-426614174000") String documentId,
    @Schema(description = "PDF 페이지 번호, 해당하지 않으면 null", example = "2") Object page) {}
