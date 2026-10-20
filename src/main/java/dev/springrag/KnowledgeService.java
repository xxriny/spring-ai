package dev.springrag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
class KnowledgeService {
    private final JdbcTemplate db;
    private final VectorStore vectors;
    private final TransactionTemplate transaction;
    private final Path uploadDir;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    KnowledgeService(JdbcTemplate db, VectorStore vectors, TransactionTemplate transaction,
                     @Value("${app.upload-dir}") String uploadDir) {
        this.db = db;
        this.vectors = vectors;
        this.transaction = transaction;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath();
    }

    record Item(UUID id, String title, String scope, String sourceUrl, String status) {}

    List<Item> list(String owner, String scope) {
        if ("PRIVATE".equals(scope)) {
            return db.query("SELECT id,title,scope,source_url,status FROM knowledge_document WHERE scope='PRIVATE' AND owner_id=? ORDER BY updated_at DESC",
                (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), owner);
        }
        return db.query("SELECT id,title,scope,source_url,status FROM knowledge_document WHERE scope='SHARED' ORDER BY updated_at DESC",
            (rs, row) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)));
    }

    Item upload(MultipartFile file, String owner, boolean admin) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : Path.of(file.getOriginalFilename()).getFileName().toString();
        String ext = extension(name);
        if (!List.of("txt", "pdf", "doc", "docx", "html", "htm").contains(ext))
            throw new IllegalArgumentException("TXT, PDF, DOC, DOCX, HTML 파일만 지원합니다");
        if (file.isEmpty()) throw new IllegalArgumentException("빈 파일입니다");
        byte[] bytes = file.getBytes();
        Files.createDirectories(uploadDir);
        Path path = uploadDir.resolve(UUID.randomUUID() + "." + ext);
        Files.write(path, bytes);
        try {
            return ingest(bytes, name, null, path.toString(), admin ? "SHARED" : "PRIVATE", admin ? null : owner);
        } catch (RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    Item ingestUrl(byte[] bytes, String title, String url) {
        return ingest(bytes, title, url, null, "SHARED", null);
    }

    private Item ingest(byte[] bytes, String title, String sourceUrl, String storedPath, String scope, String owner) {
        String hash = sha256(bytes);
        List<UUID> existing = sourceUrl == null ? List.of() : db.query(
            "SELECT id FROM knowledge_document WHERE scope=? AND source_url=?",
            (rs, row) -> rs.getObject(1, UUID.class), scope, sourceUrl);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
        if (!existing.isEmpty()) {
            String previousHash = db.queryForObject("SELECT sha256 FROM knowledge_document WHERE id=?", String.class, id);
            if (hash.equals(previousHash)) return new Item(id, title, scope, sourceUrl, "READY");
        }
        String ext = extension(sourceUrl == null ? title : java.net.URI.create(sourceUrl).getPath());
        if (sourceUrl != null && ext.isEmpty()) ext = "html";
        var resource = new ByteArrayResource(bytes) { @Override public String getFilename() { return title; } };
        List<Document> extracted = switch (ext) {
            case "pdf" -> new PagePdfDocumentReader(resource).get();
            case "doc", "docx" -> new TikaDocumentReader(resource).get();
            case "html", "htm" -> new JsoupDocumentReader(resource).get();
            default -> new TextReader(resource).get();
        };
        List<Document> chunks = new ArrayList<>();
        for (Document page : extracted) {
            if (page.getText() == null || page.getText().isBlank()) continue;
            Map<String, Object> metadata = new java.util.HashMap<>(page.getMetadata());
            metadata.put("documentId", id.toString());
            metadata.put("scope", scope);
            metadata.put("ownerId", owner == null ? "" : owner);
            metadata.put("title", title);
            metadata.put("source", sourceUrl == null ? "upload" : sourceUrl);
            chunks.addAll(splitter.apply(List.of(new Document(page.getText(), metadata))));
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("추출할 텍스트가 없습니다");
        vectors.add(chunks);
        List<String> oldChunkIds = existing.isEmpty() ? List.of() : db.query(
            "SELECT chunk_id FROM knowledge_chunk WHERE document_id=?", (rs, row) -> rs.getString(1), id);
        try {
            transaction.executeWithoutResult(status -> {
                if (existing.isEmpty()) {
                    db.update("INSERT INTO knowledge_document(id,scope,owner_id,title,source_url,stored_path,sha256) VALUES (?,?,?,?,?,?,?)",
                        id, scope, owner, title, sourceUrl, storedPath, hash);
                } else {
                    db.update("UPDATE knowledge_document SET title=?,sha256=?,updated_at=now() WHERE id=?", title, hash, id);
                    db.update("DELETE FROM knowledge_chunk WHERE document_id=?", id);
                }
                for (Document chunk : chunks) db.update("INSERT INTO knowledge_chunk(document_id,chunk_id) VALUES (?,?)", id, chunk.getId());
            });
        } catch (RuntimeException e) {
            vectors.delete(chunks.stream().map(Document::getId).toList());
            throw e;
        }
        if (!oldChunkIds.isEmpty()) vectors.delete(oldChunkIds);
        return new Item(id, title, scope, sourceUrl, "READY");
    }

    void delete(UUID id, String owner, boolean admin) {
        Integer allowed = admin
            ? db.queryForObject("SELECT count(*) FROM knowledge_document WHERE id=? AND scope='SHARED'", Integer.class, id)
            : db.queryForObject("SELECT count(*) FROM knowledge_document WHERE id=? AND scope='PRIVATE' AND owner_id=?", Integer.class, id, owner);
        if (allowed == null || allowed == 0) throw new IllegalArgumentException("문서를 찾을 수 없습니다");
        remove(id);
    }

    private void remove(UUID id) {
        List<String> chunkIds = db.query("SELECT chunk_id FROM knowledge_chunk WHERE document_id=?", (rs, row) -> rs.getString(1), id);
        if (!chunkIds.isEmpty()) vectors.delete(chunkIds);
        db.update("DELETE FROM knowledge_document WHERE id=?", id);
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
