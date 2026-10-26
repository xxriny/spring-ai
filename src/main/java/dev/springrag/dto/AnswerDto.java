package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** 대화 답변과 참조한 문서 출처를 반환한다.
 * @param conversationId 대화 ID
 * @param answer 답변 본문
 * @param sources 답변의 근거 출처
 */
@Schema(description = "대화 답변")
public record AnswerDto(
    @Schema(description = "대화 ID", example = "123e4567-e89b-12d3-a456-426614174000") UUID conversationId,
    @Schema(description = "답변 본문", example = "문서의 설명에 따르면 ... [1]") String answer,
    @Schema(description = "근거 출처 목록") List<SourceDto> sources) {}
