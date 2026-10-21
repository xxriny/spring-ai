package dev.springrag;

import dev.springrag.controller.ApiController;
import dev.springrag.dto.SearchResultDto;
import dev.springrag.service.RetrievalService;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class SearchApiTest {
    @Test void searchUsesAuthenticatedOwnerAndRejectsInvalidInput() {
        AtomicReference<String> owner = new AtomicReference<>();
        var retrieval = new RetrievalService(null, null) {
            @Override public SearchResultDto search(String query, String scope, String principal) {
                owner.set(principal);
                return new SearchResultDto(List.of(), List.of());
            }
        };
        var controller = new ApiController(null, null, null, retrieval);
        var auth = new UsernamePasswordAuthenticationToken("alice", "ignored");
        controller.search(auth, "question", "PRIVATE");
        assertEquals("alice", owner.get());
        assertEquals(HttpStatus.BAD_REQUEST, controller.badRequest(new IllegalArgumentException("bad")).getStatusCode());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.rerankerUnavailable().getStatusCode());
    }
}
