package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** API 요청 실패 이유를 반환한다.
 * @param error 사용자에게 표시할 오류 설명
 */
@Schema(description = "API 오류 응답")
public record ErrorDto(@Schema(description = "오류 설명", example = "범위가 올바르지 않습니다") String error) {}
