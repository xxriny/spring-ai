package dev.springrag.knowledge.dao;

import dev.springrag.knowledge.dto.DocumentDto;
import dev.springrag.knowledge.port.KnowledgeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

@Repository
public class KnowledgeDao implements KnowledgeRepository {
    private final JdbcTemplate db;

    public KnowledgeDao(JdbcTemplate db) { this.db = db; }

    @EventListener(ApplicationReadyEvent.class)
    public void backfillVectorCategories() {
        if (db.queryForObject("SELECT to_regclass('public.vector_store')", String.class) != null) {
            db.update("UPDATE vector_store SET metadata = jsonb_set(metadata::jsonb, '{category}', '\"uncategorized\"'::jsonb)::json "
                + "WHERE metadata::jsonb ? 'documentId' AND NOT (metadata::jsonb ? 'category') "
                + "AND metadata->>'documentId' IN (SELECT id::text FROM knowledge_document)");
        }
    }

    public List<DocumentDto> list(String owner, String scope) {
        String sql = "PRIVATE".equals(scope)
            ? "SELECT id,title,scope,source_url,category,status FROM knowledge_document WHERE scope='PRIVATE' AND owner_id=? ORDER BY updated_at DESC"
            : "SELECT id,title,scope,source_url,category,status FROM knowledge_document WHERE scope='SHARED' ORDER BY updated_at DESC";
        var mapper = (org.springframework.jdbc.core.RowMapper<DocumentDto>) (rs, row) ->
            new DocumentDto(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6));
        return "PRIVATE".equals(scope) ? db.query(sql, mapper, owner) : db.query(sql, mapper);
    }

    public Existing findUrl(String scope, String url) {
        var rows = db.query("SELECT id,sha256,category FROM knowledge_document WHERE scope=? AND source_url=?",
            (rs, row) -> new Existing(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), scope, url);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> chunkIds(UUID id) {
        return db.query("SELECT chunk_id FROM knowledge_chunk WHERE document_id=?", (rs, row) -> rs.getString(1), id);
    }

    public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
              String path, String hash, String category, List<String> chunkIds) {
        if (existing) {
            db.update("UPDATE knowledge_document SET title=?,sha256=?,category=?,updated_at=now() WHERE id=?", title, hash, category, id);
            db.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
        } else {
            db.update("INSERT INTO knowledge_document(id,scope,owner_id,title,source_url,stored_path,sha256,category) VALUES (?,?,?,?,?,?,?,?)",
                id, scope, owner, title, url, path, hash, category);
        }
        for (String chunkId : chunkIds) db.update("INSERT INTO knowledge_chunk(document_id,chunk_id) VALUES (?,?)", id, chunkId);
    }

    public boolean canDelete(UUID id, String owner, boolean admin) {
        Integer count = admin
            ? db.queryForObject("SELECT count(*) FROM knowledge_document WHERE id=? AND scope='SHARED'", Integer.class, id)
            : db.queryForObject("SELECT count(*) FROM knowledge_document WHERE id=? AND scope='PRIVATE' AND owner_id=?", Integer.class, id, owner);
        return count != null && count > 0;
    }

    public void delete(UUID id) { db.update("DELETE FROM knowledge_document WHERE id=?", id); }
}
