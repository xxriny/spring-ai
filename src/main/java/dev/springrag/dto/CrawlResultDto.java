package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** URL 수집 후 등록·건너뛰기 건수와 오류 목록을 전달한다.
 * @param indexed 색인한 페이지 수
 * @param skipped 변경이 없어 건너뛴 페이지 수
 * @param errors 페이지별 오류 메시지
 */
@Schema(description = "URL 수집 결과")
public record CrawlResultDto(
    @Schema(description = "색인한 페이지 수", example = "1") int indexed,
    @Schema(description = "변경이 없어 건너뛴 페이지 수", example = "0") int skipped,
    @Schema(description = "페이지별 오류 메시지") List<String> errors) {}
