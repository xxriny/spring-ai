package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** 방문자 브라우저가 보관할 일회성 세션 토큰과 만료 시각이다.
 * @param token 방문자 전용 Bearer 토큰
 * @param expiresAt 세션 만료 시각
 */
@Schema(description = "방문자 세션 발급 결과")
public record VisitorSessionDto(
    @Schema(description = "Authorization: Bearer 헤더에 넣을 토큰") String token,
    @Schema(description = "토큰 만료 시각(UTC)") Instant expiresAt) {}
