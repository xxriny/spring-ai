package dev.springrag.dto;

import java.util.List;

public record CrawlResultDto(int indexed, int skipped, List<String> errors) {}
