package dev.springrag;

import dev.springrag.knowledge.dao.KnowledgeDao;
import dev.springrag.knowledge.service.KnowledgeService;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import dev.springrag.infrastructure.SpringAiVectorIndex;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.mock.web.MockMultipartFile;

class KnowledgeServiceTest {
    @TempDir Path tempDir;

    @Test @DisplayName("빈 카테고리는 벡터 쓰기 전에 거절한다")
    void blankCategoryIsRejectedBeforeWritingVectors() {
        VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] { VectorStore.class }, (proxy, method, args) -> { throw new AssertionError("벡터 호출됨"); });
        var service = new KnowledgeService(new KnowledgeDao(new JdbcTemplate()), new SpringAiVectorIndex(vectors), new TransactionTemplate(), "/tmp/spring-rag-test");
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());
        assertThrows(IllegalArgumentException.class, () -> service.upload(file, " ", "user", false));
    }

    @Test @DisplayName("팔십 자를 넘는 카테고리를 거절한다")
    void categoryOverEightyCharactersIsRejected() {
        VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] { VectorStore.class }, (proxy, method, args) -> { throw new AssertionError("벡터 호출됨"); });
        var service = new KnowledgeService(new KnowledgeDao(new JdbcTemplate()), new SpringAiVectorIndex(vectors), new TransactionTemplate(), tempDir.toString());
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());
        var error = assertThrows(IllegalArgumentException.class, () -> service.upload(file, "x".repeat(81), "user", false));
        assertEquals("카테고리는 1~80자여야 합니다", error.getMessage());
    }

    @Test @DisplayName("사용자는 다른 소유자의 문서를 삭제할 수 없다")
    void userCannotDeleteDocumentOutsideOwnScope() {
        JdbcTemplate db = new JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
                if (!sql.contains("scope='PRIVATE' AND owner_id=?") || !"user".equals(args[1]))
                    throw new AssertionError("소유권 필터 누락");
                return requiredType.cast(0);
            }
            @Override public int update(String sql, Object... args) { throw new AssertionError("삭제가 실행됨"); }
        };
        VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] { VectorStore.class }, (proxy, method, args) -> { throw new AssertionError("벡터 삭제가 실행됨"); });
        var service = new KnowledgeService(new KnowledgeDao(db), new SpringAiVectorIndex(vectors), new TransactionTemplate(), "/tmp/spring-rag-test");
        assertThrows(IllegalArgumentException.class, () -> service.delete(UUID.randomUUID(), "user", false));
    }

    @Test @DisplayName("공용·개인 청크에 공백을 정리한 카테고리를 저장한다")
    void uploadStoresTrimmedCategoryOnPrivateAndSharedChunks() throws Exception {
        AtomicReference<List<Document>> added = new AtomicReference<>();
        VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] { VectorStore.class }, (proxy, method, args) -> {
                if (method.getName().equals("add")) { added.set((List<Document>) args[0]); return null; }
                throw new AssertionError(method.getName());
            });
        AtomicReference<String> expectedScope = new AtomicReference<>();
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {
                assertEquals("Spring AI", category);
                assertEquals(expectedScope.get(), scope);
                assertEquals("PRIVATE".equals(scope) ? "user" : null, owner);
                assertTrue(!chunkIds.isEmpty());
            }
        };
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        };
        var service = new KnowledgeService(repository, new SpringAiVectorIndex(vectors), new TransactionTemplate(manager), tempDir.toString());
        var file = new MockMultipartFile("file", "note.txt", "text/plain", "Spring AI memory keeps conversation messages.".getBytes());

        for (boolean admin : new boolean[] {false, true}) {
            expectedScope.set(admin ? "SHARED" : "PRIVATE");
            var item = service.upload(file, "  Spring AI  ", "user", admin);
            assertEquals("Spring AI", item.category());
            assertTrue(added.get().stream().allMatch(chunk -> "Spring AI".equals(chunk.getMetadata().get("category"))));
            assertTrue(added.get().stream().allMatch(chunk -> "qwen3-embedding:8b/1536".equals(chunk.getMetadata().get("embeddingModel"))));
        }
    }

    @Test @DisplayName("배포 색인은 Vertex 임베딩 모델 식별자를 메타데이터에 저장한다")
    void deploymentIndexStoresVertexEmbeddingIdentity() throws Exception {
        AtomicReference<List<Document>> added = new AtomicReference<>();
        VectorStore vectors = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] { VectorStore.class }, (proxy, method, args) -> {
                if (method.getName().equals("add")) { added.set((List<Document>) args[0]); return null; }
                throw new AssertionError(method.getName());
            });
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        PlatformTransactionManager manager = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        };
        var service = new KnowledgeService(repository, new SpringAiVectorIndex(vectors), new TransactionTemplate(manager),
            tempDir.toString(), image -> { throw new AssertionError("OCR 호출됨"); },
            bytes -> { throw new AssertionError("PDF 추출 호출됨"); }, "gemini-embedding-001/1536");

        service.upload(new MockMultipartFile("file", "note.txt", "text/plain", "Vertex 검색 근거".getBytes()),
            "Spring AI", "user", false);

        assertTrue(added.get().stream().allMatch(chunk ->
            "gemini-embedding-001/1536".equals(chunk.getMetadata().get("embeddingModel"))));
    }
}
