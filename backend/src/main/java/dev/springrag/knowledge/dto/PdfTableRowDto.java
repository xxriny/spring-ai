package dev.springrag.knowledge.dto;

import java.util.List;

public record PdfTableRowDto(int tableIndex, int rowIndex, List<String> headers, List<String> cells,
                             String markdown, String extractionStatus) {}
