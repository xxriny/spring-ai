package dev.springrag;

import dev.springrag.infrastructure.QwenReranker;
import dev.springrag.retrieval.port.Reranker;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class QwenRerankerTest {
    private final QwenReranker reranker = new QwenReranker("http://127.0.0.1:1/rerank");

    @Test void preservesScoresAndRejectsBrokenResponse() {
        assertEquals(List.of(2.0, -1.0), reranker.parseScores("{\"scores\":[2,-1]}", 2));
        assertThrows(Reranker.RerankerUnavailableException.class, () -> reranker.parseScores("{\"scores\":[1]}", 2));
        assertThrows(Reranker.RerankerUnavailableException.class, () -> reranker.parseScores("{\"scores\":[\"NaN\",2]}", 2));
    }

    @Test void connectionFailureIsUnavailable() {
        assertThrows(Reranker.RerankerUnavailableException.class, () -> reranker.scores("query", List.of("passage")));
    }
}
