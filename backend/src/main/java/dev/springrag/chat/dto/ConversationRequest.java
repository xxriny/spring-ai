package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 새 대화의 문서 검색 범위를 지정한다.
 * @param scope SHARED 또는 PRIVATE
 */
@Schema(description = "대화 생성 요청")
public record ConversationRequest(@Schema(description = "SHARED 또는 PRIVATE", example = "SHARED") String scope) {}
