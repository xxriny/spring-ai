package dev.springrag.dto;

import java.util.List;

public record PdfPageDto(int pageNumber, String body, boolean ocrRequired, List<PdfTableRowDto> tables) {}
