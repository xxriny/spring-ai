package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 스트리밍 답변의 출처, 생성 조각 또는 완료 신호다.
 * @param type sources, delta, done 중 하나
 * @param text delta일 때 생성된 글자 조각
 * @param sources sources일 때 답변 근거 목록
 */
@Schema(description = "대화 스트림의 NDJSON 이벤트")
public record ChatStreamEvent(
    @Schema(description = "이벤트 종류", example = "delta") String type,
    @Schema(description = "생성된 답변 조각", example = "Spring AI는") String text,
    @Schema(description = "답변 근거 출처") List<SourceDto> sources) {}
