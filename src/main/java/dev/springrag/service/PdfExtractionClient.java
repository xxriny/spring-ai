package dev.springrag.service;

import dev.springrag.dto.PdfPageDto;
import java.util.List;

public interface PdfExtractionClient {
    List<PdfPageDto> extract(byte[] pdf);

    class PdfExtractionUnavailableException extends RuntimeException {
        public PdfExtractionUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
