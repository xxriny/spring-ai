package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 방문자 공용 문서 검색 질문이다.
 * @param query 공백이 아닌 1~500자 질문
 */
@Schema(description = "방문자 공용 문서 검색 요청")
public record VisitorSearchRequest(@Schema(description = "1~500자 검색 질문", example = "Chat Memory는 어떻게 저장되나요?") String query) {}
