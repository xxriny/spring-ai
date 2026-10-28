package dev.springrag;

import dev.springrag.retrieval.dao.JdbcRetrievalDao;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class JdbcRetrievalDaoIT {
    @Test @DisplayName("배포 색인은 로컬 Ollama 벡터를 거절하고 Vertex 벡터만 허용한다")
    void deploymentIndexRejectsLocalEmbeddings() {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("paradedb/paradedb:0.26.0-pg17")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var db = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            db.execute("CREATE TABLE vector_store (id UUID PRIMARY KEY, content TEXT NOT NULL, metadata JSON)");
            UUID localChunk = UUID.randomUUID();
            db.update("INSERT INTO vector_store(id,content,metadata) VALUES (?,?,?::json)", localChunk,
                "근거", "{\"embeddingModel\":\"qwen3-embedding:8b/1536\"}");
            var dao = new JdbcRetrievalDao(db, "gemini-embedding-001/1536");

            assertThrows(IllegalStateException.class, dao::initializeIndex);
            db.update("DELETE FROM vector_store WHERE id=?", localChunk);
            dao.initializeIndex();
        }
    }

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

    @Test @DisplayName("표 셀의 이웃 조회는 같은 소유자·페이지·표·열의 제목만 가져온다")
    void tableCellFindsOnlyItsAuthorizedHeader() {
        try (var postgres = new PostgreSQLContainer<>(DockerImageName.parse("paradedb/paradedb:0.26.0-pg17")
                .asCompatibleSubstituteFor("postgres"))) {
            postgres.start();
            var db = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
            db.execute("CREATE TABLE vector_store (id UUID PRIMARY KEY, content TEXT NOT NULL, metadata JSON)");
            UUID same = UUID.randomUUID(), otherOwner = UUID.randomUUID(), otherColumn = UUID.randomUUID();
            UUID otherPage = UUID.randomUUID(), otherTable = UUID.randomUUID();
            insertTable(db, same, "alice", 1, 1, 1, "HEADER");
            insertTable(db, otherOwner, "bob", 1, 1, 1, "HEADER");
            insertTable(db, otherColumn, "alice", 1, 1, 2, "HEADER");
            insertTable(db, otherPage, "alice", 2, 1, 1, "HEADER");
            insertTable(db, otherTable, "alice", 1, 2, 1, "HEADER");
            var anchor = new Document(UUID.randomUUID().toString(), "셀 값", Map.of(
                "documentId", "pdf", "chunkOrder", 7, "page_number", 1,
                "tableIndex", 1, "columnIndex", 1, "tablePart", "CELL"));

            var matches = new JdbcRetrievalDao(db).neighbors(anchor, "PRIVATE", "alice");

            assertEquals(1, matches.size());
            assertEquals(same.toString(), matches.get(0).getId());
            assertTrue(new JdbcRetrievalDao(db).neighbors(anchor, "SHARED", "alice").isEmpty());
        }
    }

    private static void insertTable(JdbcTemplate db, UUID id, String owner, int page, int table, int column, String part) {
        String metadata = "{\"scope\":\"PRIVATE\",\"ownerId\":\"" + owner
            + "\",\"documentId\":\"pdf\",\"chunkOrder\":1,\"page_number\":" + page
            + ",\"tableIndex\":" + table + ",\"columnIndex\":" + column
            + ",\"tablePart\":\"" + part + "\"}";
        db.update("INSERT INTO vector_store(id,content,metadata) VALUES (?,?,?::json)", id, "채용 지원 자격", metadata);
    }

    private static void insert(JdbcTemplate db, UUID id, String text, String owner, int order) {
        String metadata = "{\"scope\":\"PRIVATE\",\"ownerId\":\"" + owner
            + "\",\"documentId\":\"doc-" + owner + "\",\"chunkOrder\":" + order + "}";
        db.update("INSERT INTO vector_store(id,content,metadata) VALUES (?,?,?::json)", id, text, metadata);
    }
}
