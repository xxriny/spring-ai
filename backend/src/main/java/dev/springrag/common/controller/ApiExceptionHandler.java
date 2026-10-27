package dev.springrag.common.controller;

import dev.springrag.common.dto.ErrorDto;
import dev.springrag.chat.service.ChatService;
import dev.springrag.knowledge.port.OcrClient;
import dev.springrag.knowledge.port.PdfExtractionClient;
import dev.springrag.retrieval.port.Reranker;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 컨트롤러에서 전파된 검증·외부 서비스 오류를 공통 HTTP 응답으로 바꾼다. */
@RestControllerAdvice
public class ApiExceptionHandler {
    /**
     * 요청 값과 소유권 검증 오류를 API 오류 형식으로 변환한다.
     *
     * @param e 검증 오류
     * @return HTTP 400 응답
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorDto> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorDto(e.getMessage()));
    }

    /**
     * 생성 모델이 준비되지 않은 요청을 서비스 불가로 응답한다.
     *
     * @return HTTP 503 응답
     */
    @ExceptionHandler(ChatService.ChatUnavailableException.class)
    public ResponseEntity<ErrorDto> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("생성용 채팅 모델을 사용할 수 없습니다."));
    }

    /**
     * 검색 재정렬 서버 장애를 서비스 불가로 응답한다.
     *
     * @return HTTP 503 응답
     */
    @ExceptionHandler(Reranker.RerankerUnavailableException.class)
    public ResponseEntity<ErrorDto> rerankerUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("리랭커를 사용할 수 없습니다."));
    }

    /**
     * OCR 서버 장애를 서비스 불가로 응답한다.
     *
     * @return HTTP 503 응답
     */
    @ExceptionHandler(OcrClient.OcrUnavailableException.class)
    public ResponseEntity<ErrorDto> ocrUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("OCR을 사용할 수 없습니다."));
    }

    /**
     * PDF 추출 서버 장애를 서비스 불가로 응답한다.
     *
     * @return HTTP 503 응답
     */
    @ExceptionHandler(PdfExtractionClient.PdfExtractionUnavailableException.class)
    public ResponseEntity<ErrorDto> pdfExtractionUnavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(new ErrorDto("PDF 추출 서비스를 사용할 수 없습니다."));
    }
}
