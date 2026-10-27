package dev.springrag.knowledge.port;

import dev.springrag.knowledge.dto.PdfPageDto;
import java.util.List;

/** PDF의 본문과 표를 추출하는 외부 서비스 계약이다. */
public interface PdfExtractionClient {
    /**
     * @param pdf PDF 원본 바이트
     * @return 페이지별 본문과 표 행
     * @throws PdfExtractionUnavailableException 추출 서비스를 사용할 수 없는 경우
     */
    List<PdfPageDto> extract(byte[] pdf);

    /** PDF 추출 서비스 장애를 표시한다. */
    class PdfExtractionUnavailableException extends RuntimeException {
        /** @param message 내부 오류 설명 @param cause 원인 예외 */
        public PdfExtractionUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
