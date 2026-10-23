package dev.springrag;

import dev.springrag.dao.JdbcRetrievalDao;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class JdbcRetrievalDaoIT {
    @Test void indexesExistingRowsAndKeepsBm25AndNeighborsWithinOwner() {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("paradedb/paradedb:0.26.0-pg17")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var db = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            db.execute("CREATE TABLE vector_store (id UUID PRIMARY KEY, content TEXT NOT NULL, metadata JSON)");
            UUID first = UUID.randomUUID(), second = UUID.randomUUID(), bob = UUID.randomUUID();
            insert(db, first, "rare spring retrieval", "alice", 0);
            insert(db, second, "adjacent evidence", "alice", 1);
            insert(db, bob, "rare spring retrieval", "bob", 0);
            var dao = new JdbcRetrievalDao(db);
            assertThrows(IllegalStateException.class, dao::initializeIndex);
            db.update("UPDATE vector_store SET metadata=jsonb_set(metadata::jsonb, '{embeddingModel}', to_jsonb(?::text))::json", "qwen3-embedding:8b/1536");
            dao.initializeIndex();
            var hits = dao.bm25("rare retrieval", "PRIVATE", "alice", 10);
            assertEquals(1, hits.size());
            assertEquals(first.toString(), hits.get(0).getId());
            assertEquals(second.toString(), dao.neighbors(hits.get(0), "PRIVATE", "alice").get(0).getId());
            assertTrue(dao.bm25("rare retrieval", "SHARED", "alice", 10).isEmpty());
            db.update("UPDATE vector_store SET content='revised passage' WHERE id=?", first);
            assertTrue(dao.bm25("rare retrieval", "PRIVATE", "alice", 10).isEmpty());
            db.update("DELETE FROM vector_store WHERE id=?", second);
            assertTrue(dao.neighbors(hits.get(0), "PRIVATE", "alice").isEmpty());
        }
    }

    private static void insert(JdbcTemplate db, UUID id, String text, String owner, int order) {
        String metadata = "{\"scope\":\"PRIVATE\",\"ownerId\":\"" + owner
            + "\",\"documentId\":\"doc-" + owner + "\",\"chunkOrder\":" + order + "}";
        db.update("INSERT INTO vector_store(id,content,metadata) VALUES (?,?,?::json)", id, text, metadata);
    }
}
