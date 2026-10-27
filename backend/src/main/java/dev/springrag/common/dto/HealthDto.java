package dev.springrag.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 서비스 상태 응답이다.
 * @param status 서비스 상태
 */
@Schema(description = "서비스 상태")
public record HealthDto(@Schema(description = "서비스 상태", example = "UP") String status) {}
