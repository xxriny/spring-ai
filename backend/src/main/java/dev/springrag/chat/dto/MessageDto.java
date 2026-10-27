package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 대화 화면에 표시할 저장 메시지다.
 * @param role user 또는 assistant
 * @param body 메시지 본문
 * @param sources 답변 출처, 질문은 빈 목록
 */
@Schema(description = "저장된 대화 메시지")
public record MessageDto(
    @Schema(description = "user 또는 assistant", example = "assistant") String role,
    @Schema(description = "메시지 본문", example = "문서의 설명에 따르면 ... [1]") String body,
    @Schema(description = "답변 출처, 질문은 빈 목록") List<SourceDto> sources) {}
