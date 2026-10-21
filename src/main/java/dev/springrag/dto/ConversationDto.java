package dev.springrag.dto;

import java.util.UUID;

public record ConversationDto(UUID id, String title, String scope) {}
