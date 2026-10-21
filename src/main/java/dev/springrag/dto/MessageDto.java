package dev.springrag.dto;

import java.util.List;

public record MessageDto(String role, String body, List<SourceDto> sources) {}
