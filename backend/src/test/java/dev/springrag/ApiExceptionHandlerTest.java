package dev.springrag;

import dev.springrag.common.controller.ApiExceptionHandler;
import dev.springrag.knowledge.port.PdfExtractionClient;
import dev.springrag.chat.service.VisitorRateLimitException;
import dev.springrag.chat.service.VisitorUnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class ApiExceptionHandlerTest {
    @Test
    @DisplayName("PDF 추출 서비스 장애는 문서 등록 오류를 503으로 반환한다")
    void pdfExtractionFailureReturnsServiceUnavailable() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingPdfController())
            .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/test/pdf"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error").value("PDF 추출 서비스를 사용할 수 없습니다."));
    }

    @Test
    @DisplayName("만료된 방문자 토큰과 일일 한도 초과를 각각 401·429로 반환한다")
    void visitorErrorsHaveDistinctStatuses() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FailingVisitorController())
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(post("/test/visitor/auth")).andExpect(status().isUnauthorized());
        mvc.perform(post("/test/visitor/limit")).andExpect(status().isTooManyRequests());
    }

    @RestController
    static class FailingPdfController {
        @GetMapping("/test/pdf")
        String extract() {
            throw new PdfExtractionClient.PdfExtractionUnavailableException("내부 연결 정보", null);
        }
    }


    @RestController
    static class FailingVisitorController {
        @org.springframework.web.bind.annotation.PostMapping("/test/visitor/auth")
        String auth() { throw new VisitorUnauthorizedException(); }
        @org.springframework.web.bind.annotation.PostMapping("/test/visitor/limit")
        String limit() { throw new VisitorRateLimitException(); }
    }
}
