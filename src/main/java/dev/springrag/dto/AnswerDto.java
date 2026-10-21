package dev.springrag.dto;

import java.util.List;
import java.util.UUID;

public record AnswerDto(UUID conversationId, String answer, List<SourceDto> sources) {}
