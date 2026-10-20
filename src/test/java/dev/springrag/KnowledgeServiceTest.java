package dev.springrag;

import static org.junit.jupiter.api.Assertions.assertThrows;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeServiceTest {
    @Test void userCannotDeleteDocumentOutsideOwnScope() {
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
        var service = new KnowledgeService(db, vectors, new TransactionTemplate(), "/tmp/spring-rag-test");
        assertThrows(IllegalArgumentException.class, () -> service.delete(UUID.randomUUID(), "user", false));
    }
}
