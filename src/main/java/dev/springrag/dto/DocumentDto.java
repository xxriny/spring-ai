package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 등록된 지식 문서의 접근 범위와 출처를 나타낸다.
 * @param id 문서 ID
 * @param title 문서 제목
 * @param scope 공용 또는 개인 범위
 * @param sourceUrl URL 수집 문서의 출처, 업로드 문서는 null
 * @param category 문서 카테고리
 * @param status 색인 상태
 */
@Schema(description = "등록된 지식 문서")
public record DocumentDto(
    @Schema(description = "문서 ID", example = "123e4567-e89b-12d3-a456-426614174000") UUID id,
    @Schema(description = "문서 제목", example = "Spring AI ETL") String title,
    @Schema(description = "SHARED 또는 PRIVATE", example = "SHARED") String scope,
    @Schema(description = "URL 수집 문서의 출처, 업로드 문서는 null", example = "https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html") String sourceUrl,
    @Schema(description = "문서 카테고리", example = "Spring AI docs") String category,
    @Schema(description = "색인 상태", example = "READY") String status) {}
