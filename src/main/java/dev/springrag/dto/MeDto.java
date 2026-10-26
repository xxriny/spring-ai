package dev.springrag.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 현재 로그인한 사용자의 공개 계정 정보다.
 * @param username 로그인 이름
 * @param admin 관리자 여부
 */
@Schema(description = "현재 사용자")
public record MeDto(
    @Schema(description = "로그인 이름", example = "user") String username,
    @Schema(description = "관리자 여부", example = "false") boolean admin) {}
