package dev.springrag;

import dev.springrag.retrieval.port.Reranker;
import dev.springrag.retrieval.service.RetrievalService;
import dev.springrag.retrieval.port.RetrievalDao;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import dev.springrag.common.port.VectorIndex;
import dev.springrag.infrastructure.SpringAiVectorIndex;

class RetrievalServiceTest {
    @Test @DisplayName("BM25 단독 결과도 결합하고 상위 세 건만 passage로 만든다")
    void bm25OnlyHitSurvivesFusionAndOnlyTopThreeBecomePassages() {
        var vectors = vectorStore(new AtomicReference<>(), List.of(
            doc("vector-1", "vector answer", "SHARED", "", "doc-v", 0),
            doc("both", "shared answer", "SHARED", "", "doc-b", 0)));
        var lexical = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) {
                return List.of(doc("both", "shared answer", "SHARED", "", "doc-b", 0),
                    doc("lexical", "rare exact phrase", "SHARED", "", "doc-l", 0));
            }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) { return List.of(); }
        };
        var result = new RetrievalService(vectors, (query, texts) ->
            texts.stream().map(text -> text.contains("rare") ? 10.0 : 1.0).toList(), lexical)
            .search("rare phrase", "SHARED", "alice");
        assertEquals(List.of("both", "vector-1", "lexical"), result.candidates().stream().map(h -> h.chunkId()).toList());
        assertEquals("lexical", result.reranked().get(0).chunkId());
        assertEquals(3, result.passages().size());
        assertEquals(2, result.vectorCandidates().size());
        assertEquals(2, result.bm25Candidates().size());
    }

    @Test @DisplayName("PDF 본문을 같은 페이지에서 확장하고 중복을 제거한다")
    void passageExpansionKeepsPdfBodyOnSamePageAndRemovesOverlap() {
        var center = new Document("center", "앞 문장. 핵심 답변이 여기 있습니다.",
            Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc", "chunkOrder", 1,
                "contentType", "BODY", "page_number", 2));
        var lexical = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) {
                assertEquals("PRIVATE", scope);
                assertEquals("alice", owner);
                return List.of(new Document("previous", "앞 문장.",
                    Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc", "chunkOrder", 0,
                        "contentType", "BODY", "page_number", 2)),
                    new Document("table", "| 표 | 값 |",
                    Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc", "chunkOrder", 2,
                        "contentType", "TABLE_ROW", "page_number", 2)),
                    new Document("missing-page", "다른 페이지의 문장",
                    Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc", "chunkOrder", 2,
                        "contentType", "BODY")));
            }
        };
        var result = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(center)),
            (query, texts) -> List.of(1.0), lexical).search("핵심 답변", "PRIVATE", "alice");
        assertEquals("previous", result.passages().get(0).sourceChunkIds().get(0));
        assertFalse(result.passages().get(0).text().contains("| 표 |"));
        assertFalse(result.passages().get(0).text().contains("다른 페이지의 문장"));
        assertEquals(1, result.passages().get(0).text().split("앞 문장\\.", -1).length - 1);
    }

    @Test @DisplayName("재정렬 열 건과 passage 세 건의 토큰 한도를 지킨다")
    void fusionCapsRerankerAtTenAndPassagesAtThreeWithinTokenBudget() {
        String longText = "관련 근거 문장과 부연 설명. ".repeat(800);
        List<Document> semantic = new ArrayList<>();
        List<Document> lexical = new ArrayList<>();
        for (int i = 0; i < 10; i++) semantic.add(doc("v" + i, longText, "SHARED", "", "d" + i, 0));
        for (int i = 0; i < 10; i++) lexical.add(doc("b" + i, longText, "SHARED", "", "l" + i, 0));
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return lexical; }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) { return List.of(); }
        };
        var result = new RetrievalService(vectorStore(new AtomicReference<>(), semantic),
            (query, texts) -> {
                assertEquals(10, texts.size());
                return texts.stream().map(text -> 1.0).toList();
            }, dao).search("근거", "SHARED", "alice");
        assertEquals(10, result.candidates().size());
        assertEquals(3, result.passages().size());
        var tokens = new JTokkitTokenCountEstimator();
        assertTrue(result.passages().stream().allMatch(p -> tokens.estimate(p.text()) <= 1200));
        assertTrue(result.passages().stream().mapToInt(p -> tokens.estimate(p.text())).sum() <= 3600);
    }

    @Test @DisplayName("인접 PDF 표 행의 마크다운 경계를 유지한다")
    void adjacentPdfTableRowsKeepMarkdownRowBoundaries() {
        var row = new Document("row-1", "| 항목 | 값 |", Map.of("scope", "SHARED",
            "documentId", "pdf", "chunkOrder", 4, "contentType", "TABLE_ROW", "page_number", 2, "tableIndex", 1));
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) {
                return List.of(new Document("row-2", "| 두 번째 | 2 |", Map.of("scope", "SHARED",
                    "documentId", "pdf", "chunkOrder", 5, "contentType", "TABLE_ROW", "page_number", 2, "tableIndex", 1)));
            }
        };
        var result = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(row)),
            (query, texts) -> List.of(1.0), dao).search("값", "SHARED", "alice");
        assertEquals("| 항목 | 값 |\n| 두 번째 | 2 |", result.passages().get(0).text());
    }

    @Test @DisplayName("뒤쪽 표 행의 passage에 같은 열의 긴 제목과 셀 값을 함께 담는다")
    void laterPdfTableCellIncludesItsHeader() {
        var cell = new Document("cell-3", "페이지 1 · 표 1 · 행 3\n| 열 1 내용 | 승인 기준 |", Map.of(
            "scope", "PRIVATE", "ownerId", "alice", "documentId", "pdf", "chunkOrder", 7,
            "contentType", "TABLE_ROW", "page_number", 1, "tableIndex", 1,
            "rowIndex", 3, "columnIndex", 1, "tablePart", "CELL"));
        var header = new Document("header", "페이지 1 · 표 1 · 행 1\n| 열 1 제목 | 채용 지원 자격 |", Map.of(
            "scope", "PRIVATE", "ownerId", "alice", "documentId", "pdf", "chunkOrder", 1,
            "contentType", "TABLE_ROW", "page_number", 1, "tableIndex", 1,
            "rowIndex", 1, "columnIndex", 1, "tablePart", "HEADER"));
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) {
                assertEquals("PRIVATE", scope);
                assertEquals("alice", owner);
                return List.of(header);
            }
        };

        var result = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(cell)),
            (query, texts) -> List.of(1.0), dao).search("채용 지원 자격의 승인 기준", "PRIVATE", "alice");

        assertTrue(result.passages().get(0).text().contains("채용 지원 자격"));
        assertTrue(result.passages().get(0).text().contains("승인 기준"));
        assertTrue(result.passages().get(0).sourceChunkIds().contains("header"));
    }

    @Test @DisplayName("긴 표 제목 조각이 많아도 질문과 관련된 제목과 셀 값을 보존한다")
    void tableHeaderFragmentsDoNotPushCellOutOfPassage() {
        var cell = new Document("cell", "| 열 1 내용 | 승인 기준: 3년 |", Map.of(
            "scope", "SHARED", "documentId", "pdf", "chunkOrder", 20,
            "contentType", "TABLE_ROW", "page_number", 1, "tableIndex", 1,
            "columnIndex", 1, "tablePart", "CELL"));
        List<Document> headers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            headers.add(new Document("header-" + i,
                "열 1 제목: " + "일반 안내 ".repeat(250) + (i == 7 ? "채용 지원 자격" : "기타 설명"),
                Map.of("scope", "SHARED", "documentId", "pdf", "chunkOrder", i,
                    "contentType", "TABLE_ROW", "page_number", 1, "tableIndex", 1,
                    "columnIndex", 1, "tablePart", "HEADER")));
        }
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) { return headers; }
        };

        var passage = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(cell)),
            (query, texts) -> List.of(1.0), dao).search("채용 지원 자격 승인 기준", "SHARED", "alice").passages().get(0);

        assertTrue(passage.text().contains("채용 지원 자격"));
        assertTrue(passage.text().contains("승인 기준: 3년"));
        assertTrue(passage.sourceChunkIds().contains("header-7"));
        assertTrue(new JTokkitTokenCountEstimator().estimate(passage.text()) <= 1200);
    }

    @Test @DisplayName("HTML의 서로 다른 절을 섞지 않는다")
    void adjacentHtmlSectionsStaySeparate() {
        var anchor = new Document("section-a", "첫 제목의 근거", Map.of("scope", "SHARED",
            "documentId", "html", "chunkOrder", 3, "section", "첫 제목"));
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document item, String scope, String owner) {
                return List.of(new Document("section-b", "다른 제목의 내용", Map.of("scope", "SHARED",
                    "documentId", "html", "chunkOrder", 4, "section", "다른 제목")));
            }
        };
        var result = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(anchor)),
            (query, texts) -> List.of(1.0), dao).search("근거", "SHARED", "alice");
        assertEquals("첫 제목의 근거", result.passages().get(0).text());
    }

    @Test @DisplayName("이전 청크의 끝에서 최대 이백 토큰을 사용한다")
    void previousNeighborUsesAtMostTwoHundredTokensFromItsEnd() {
        String previous = "a ".repeat(600) + "긴문장".repeat(1000);
        var anchor = doc("anchor", "anchor", "SHARED", "", "doc", 1);
        var neighbor = doc("previous", previous, "SHARED", "", "doc", 0);
        var dao = new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document item, String scope, String owner) { return List.of(neighbor); }
        };
        var result = new RetrievalService(vectorStore(new AtomicReference<>(), List.of(anchor)),
            (query, texts) -> List.of(1.0), dao).search("anchor", "SHARED", "alice");
        String text = result.passages().get(0).text();
        assertTrue(new JTokkitTokenCountEstimator().estimate(text.substring(0, text.lastIndexOf("anchor")).trim()) <= 200);
    }

    private static Document doc(String id, String text, String scope, String owner, String documentId, int order) {
        return new Document(id, text, Map.of("scope", scope, "ownerId", owner,
            "documentId", documentId, "chunkOrder", order, "title", "문서", "source", "upload"));
    }
    @Test @DisplayName("개인 문서는 현재 소유자로 제한하고 열 건을 재정렬한다")
    void privateScopeUsesCurrentOwnerAndReranksTopTen() {
        AtomicReference<SearchRequest> request = new AtomicReference<>();
        var vectors = vectorStore(request, List.of(
            new Document("first", Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc1")),
            new Document("second", Map.of("scope", "PRIVATE", "ownerId", "alice", "documentId", "doc2"))));
        Reranker reranker = (query, passages) -> List.of(-2.0, 4.0);
        var result = new RetrievalService(vectors, reranker, emptyDao()).search("question", "PRIVATE", "alice");
        assertEquals(10, request.get().getTopK());
        assertEquals(0.3, request.get().getSimilarityThreshold());
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=PRIVATE]"));
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=alice]"));
        assertEquals("second", result.reranked().get(0).text());
        assertEquals(4.0, result.reranked().get(0).rerankerScore());
        assertEquals("first", result.candidates().get(0).text());
    }

    @Test @DisplayName("빈 검색은 리랭커를 건너뛰고 잘못된 범위는 거절한다")
    void emptySearchSkipsRerankerAndRejectsBadScope() {
        AtomicReference<SearchRequest> request = new AtomicReference<>();
        var service = new RetrievalService(vectorStore(request, List.of()), (query, passages) -> {
            throw new AssertionError("Qwen should not be called");
        }, emptyDao());
        assertTrue(service.search("question", "SHARED", "alice").reranked().isEmpty());
        assertTrue(request.get().getFilterExpression().toString().contains("Value[value=SHARED]"));
        assertThrows(IllegalArgumentException.class, () -> service.search("question", "OTHER", "alice"));
    }

    private static VectorIndex vectorStore(AtomicReference<SearchRequest> request, List<Document> docs) {
        return new SpringAiVectorIndex((VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(), new Class<?>[] {VectorStore.class},
            (proxy, method, args) -> {
                if (method.getName().equals("similaritySearch")) { request.set((SearchRequest) args[0]); return docs; }
                throw new AssertionError(method.getName());
            }));
    }

    private static RetrievalDao emptyDao() {
        return new RetrievalDao() {
            @Override public List<Document> bm25(String query, String scope, String owner, int limit) { return List.of(); }
            @Override public List<Document> neighbors(Document anchor, String scope, String owner) { return List.of(); }
        };
    }
}
