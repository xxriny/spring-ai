package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 대화에 보낼 질문이다.
 * @param text 공백이 아닌 1~4000자 질문
 */
@Schema(description = "대화 질문 요청")
public record QuestionRequest(@Schema(description = "1~4000자 질문", example = "이 문서의 핵심은 무엇인가요?") String text) {}
