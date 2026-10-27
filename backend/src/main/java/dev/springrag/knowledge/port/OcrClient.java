package dev.springrag.knowledge.port;

/** 이미지에서 문서 본문을 추출하는 외부 OCR 계약이다. */
public interface OcrClient {
    /**
     * @param image 이미지 바이트
     * @return 인식한 본문
     * @throws OcrUnavailableException OCR 서비스를 사용할 수 없는 경우
     */
    String recognize(byte[] image);

    /** OCR 서비스 장애를 표시한다. */
    class OcrUnavailableException extends RuntimeException {
        /** @param message 내부 오류 설명 @param cause 원인 예외 */
        public OcrUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
