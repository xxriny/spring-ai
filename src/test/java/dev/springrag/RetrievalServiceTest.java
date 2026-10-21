package dev.springrag;

import dev.springrag.service.Reranker;
import dev.springrag.service.RetrievalService;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

class RetrievalServiceTest {
    @Test void privateScopeUsesCurrentOwnerAndReranksTopTen() {
        AtomicReference<SearchRequest> request = new AtomicReference<>();
        var vectors = vectorStore(request, List.of(
            new Document("first", Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc1")),
            new Document("second", Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc2"))));
        Reranker reranker = (query, passages) -> List.of(-2.0, 4.0);
        var result = new RetrievalService(vectors, reranker).search("question", "PRIVATE", "alice");
        assertEquals(10, request.get().getTopK());
        assertEquals(0.3, request.get().getSimilarityThreshold());
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=PRIVATE]"));
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=alice]"));
        assertEquals("second", result.reranked().get(0).text());
        assertEquals(4.0, result.reranked().get(0).rerankerScore());
        assertEquals("first", result.candidates().get(0).text());
    }

    @Test void emptySearchSkipsRerankerAndRejectsBadScope() {
        AtomicReference<SearchRequest> request = new AtomicReference<>();
        var service = new RetrievalService(vectorStore(request, List.of()), (query, passages) -> {
            throw new AssertionError("Qwen should not be called");
        });
        assertTrue(service.search("question", "SHARED", "alice").reranked().isEmpty());
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=SHARED]"));
        assertThrows(IllegalArgumentException.class, () -> service.search("question", "OTHER", "alice"));
    }

    private static VectorStore vectorStore(AtomicReference<SearchRequest> request, List<Document> docs) {
        return (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(), new Class<?>[] {VectorStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("similaritySearch")) { request.set((SearchRequest) args[0]); return docs; }
                throw new AssertionError(method.getName());
            });
    }
}
