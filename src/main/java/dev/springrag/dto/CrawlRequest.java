package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 관리자 URL 수집의 시작점과 범위를 지정한다.
 * @param url 시작 URL
 * @param category 등록할 문서 카테고리
 * @param maxPages 최대 수집 페이지 수, null이면 100
 */
@Schema(description = "관리자 URL 수집 요청")
public record CrawlRequest(
    @Schema(description = "시작 URL", example = "https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html") String url,
    @Schema(description = "문서 카테고리", example = "Spring AI docs") String category,
    @Schema(description = "최대 수집 페이지 수, 생략하면 100", example = "1") Integer maxPages) {}
