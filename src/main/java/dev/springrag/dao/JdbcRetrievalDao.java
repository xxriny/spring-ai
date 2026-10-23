package dev.springrag.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.springrag.config.LocalEmbeddingConfig;
import java.util.List;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRetrievalDao implements RetrievalDao {
    private static final String COLUMNS = "SELECT id,content,metadata FROM vector_store WHERE ";
    private final JdbcTemplate db;
    private final ObjectMapper json = new ObjectMapper();
    private final RowMapper<Document> mapper = (rs, row) ->
        new Document(rs.getString("id"), rs.getString("content"), metadata(rs.getString("metadata")));

    public JdbcRetrievalDao(JdbcTemplate db) {
        this.db = db;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeIndex() {
        Long incompatible = db.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'embeddingModel' IS DISTINCT FROM ?",
            Long.class, LocalEmbeddingConfig.EMBEDDING_ID);
        if (incompatible != null && incompatible > 0)
            throw new IllegalStateException("기존 임베딩 문서가 있습니다. Qwen3-Embedding-8B로 재색인한 뒤 시작하세요");
        db.execute("CREATE EXTENSION IF NOT EXISTS pg_search");
        db.execute("CREATE INDEX IF NOT EXISTS vector_store_bm25_idx ON vector_store USING paradedb (id, content)");
    }

    @Override public List<Document> bm25(String query, String scope, String owner, int limit) {
        String sql = COLUMNS + "content ||| ? AND metadata->>'scope'=? "
            + ("PRIVATE".equals(scope) ? "AND metadata->>'ownerId'=? " : "")
            + "ORDER BY pdb.score(id) DESC LIMIT ?";
        return "PRIVATE".equals(scope)
            ? db.query(sql, mapper, query, scope, owner, limit)
            : db.query(sql, mapper, query, scope, limit);
    }

    @Override public List<Document> neighbors(Document anchor, String scope, String owner) {
        Map<String, Object> metadata = anchor.getMetadata();
        Object documentId = metadata.get("documentId"), chunkOrder = metadata.get("chunkOrder");
        if (documentId == null || chunkOrder == null) return List.of();
        int order = chunkOrder instanceof Number number ? number.intValue() : Integer.parseInt(chunkOrder.toString());
        String sql = COLUMNS + "metadata->>'documentId'=? AND metadata->>'chunkOrder' IN (?,?) "
            + "AND metadata->>'scope'=? " + ("PRIVATE".equals(scope) ? "AND metadata->>'ownerId'=? " : "");
        return "PRIVATE".equals(scope)
            ? db.query(sql, mapper, documentId.toString(), String.valueOf(order - 1), String.valueOf(order + 1), scope, owner)
            : db.query(sql, mapper, documentId.toString(), String.valueOf(order - 1), String.valueOf(order + 1), scope);
    }

    private Map<String, Object> metadata(String value) {
        try { return json.readValue(value, new TypeReference<Map<String, Object>>() {}); }
        catch (JsonProcessingException e) { throw new IllegalStateException("검색 메타데이터를 읽을 수 없습니다", e); }
    }
}
