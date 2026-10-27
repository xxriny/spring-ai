package dev.springrag.chat.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** 사용자에게 보이는 대화와 고정 검색 범위다.
 * @param id 대화 ID
 * @param title 대화 제목
 * @param scope SHARED 또는 PRIVATE
 */
@Schema(description = "대화 정보")
public record ConversationDto(
    @Schema(description = "대화 ID", example = "123e4567-e89b-12d3-a456-426614174000") UUID id,
    @Schema(description = "대화 제목", example = "새 대화") String title,
    @Schema(description = "고정 검색 범위", example = "SHARED") String scope) {}
