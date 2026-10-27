package dev.springrag;

import dev.springrag.common.controller.ApiExceptionHandler;
import dev.springrag.retrieval.controller.SearchController;
import dev.springrag.retrieval.dto.SearchResultDto;
import dev.springrag.retrieval.service.RetrievalService;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class SearchApiTest {
    @Test @DisplayName("검색은 인증 사용자로 실행하고 오류를 공통 형식으로 반환한다")
    void searchUsesAuthenticatedOwnerAndRejectsInvalidInput() {
        AtomicReference<String> owner = new AtomicReference<>();
        var retrieval = new RetrievalService(null, null, null) {
            @Override public SearchResultDto search(String query, String scope, String principal) {
                owner.set(principal);
                return new SearchResultDto(List.of(), List.of());
            }
        };
        var controller = new SearchController(retrieval);
        var auth = new UsernamePasswordAuthenticationToken("alice", "ignored");
        controller.search(auth, "question", "PRIVATE");
        assertEquals("alice", owner.get());
        var errors = new ApiExceptionHandler();
        assertEquals(HttpStatus.BAD_REQUEST, errors.badRequest(new IllegalArgumentException("bad")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, errors.rerankerUnavailable().getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, errors.ocrUnavailable().getStatusCode());
    }
}
