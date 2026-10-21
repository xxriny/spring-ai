package dev.springrag.dto;

import java.util.UUID;

public record DocumentDto(UUID id, String title, String scope, String sourceUrl, String category, String status) {}
