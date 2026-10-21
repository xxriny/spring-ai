package dev.springrag.service;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.dto.DocumentDto;
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
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.jsoup.Jsoup;

@Service
public class KnowledgeService {
    private final KnowledgeDao repository;
    private final VectorStore vectors;
    private final TransactionTemplate transaction;
    private final Path uploadDir;
    private final TokenTextSplitter splitter = new TokenTextSplitter();

    public KnowledgeService(KnowledgeDao repository, VectorStore vectors, TransactionTemplate transaction,
                     @Value("${app.upload-dir}") String uploadDir) {
        this.repository = repository;
        this.vectors = vectors;
        this.transaction = transaction;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath();
    }

    public List<DocumentDto> list(String owner, String scope) {
        return repository.list(owner, scope);
    }

    public DocumentDto upload(MultipartFile file, String category, String owner, boolean admin) throws IOException {
        String normalizedCategory = normalizeCategory(category);
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
            return ingest(bytes, name, null, path.toString(), admin ? "SHARED" : "PRIVATE", admin ? null : owner, normalizedCategory);
        } catch (RuntimeException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    public DocumentDto ingestUrl(byte[] bytes, String title, String url, String category) {
        return ingest(bytes, title, url, null, "SHARED", null, normalizeCategory(category));
    }

    private DocumentDto ingest(byte[] bytes, String title, String sourceUrl, String storedPath, String scope, String owner, String category) {
        String hash = sha256(bytes);
        var existing = sourceUrl == null ? null : repository.findUrl(scope, sourceUrl);
        UUID id = existing == null ? UUID.randomUUID() : existing.id();
        if (existing != null && hash.equals(existing.hash()) && category.equals(existing.category()))
            return new DocumentDto(id, title, scope, sourceUrl, category, "READY");
        String ext = extension(sourceUrl == null ? title : java.net.URI.create(sourceUrl).getPath());
        if (sourceUrl != null && ext.isEmpty()) ext = "html";
        var resource = new ByteArrayResource(bytes) { @Override public String getFilename() { return title; } };
        List<Document> extracted = switch (ext) {
            case "pdf" -> new PagePdfDocumentReader(resource).get();
            case "doc", "docx" -> new TikaDocumentReader(resource).get();
            case "html", "htm" -> extractHtml(bytes);
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
            metadata.put("category", category);
            for (Document chunk : splitter.apply(List.of(new Document(page.getText(), metadata)))) {
                var ordered = new java.util.HashMap<>(chunk.getMetadata());
                ordered.put("chunkOrder", chunks.size());
                chunks.add(new Document(chunk.getText(), ordered));
            }
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("추출할 텍스트가 없습니다");
        List<String> newChunkIds = chunks.stream().map(Document::getId).toList();
        List<String> oldChunkIds;
        try {
            vectors.add(chunks);
            oldChunkIds = existing == null ? List.of() : repository.chunkIds(id);
            transaction.executeWithoutResult(status -> {
                repository.save(id, existing != null, scope, owner, title, sourceUrl, storedPath, hash, category, newChunkIds);
                if (!oldChunkIds.isEmpty()) vectors.delete(oldChunkIds);
            });
        } catch (RuntimeException e) {
            try { vectors.delete(newChunkIds); }
            catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            throw e;
        }
        return new DocumentDto(id, title, scope, sourceUrl, category, "READY");
    }

    public void delete(UUID id, String owner, boolean admin) {
        if (!repository.canDelete(id, owner, admin)) throw new IllegalArgumentException("문서를 찾을 수 없습니다");
        remove(id);
    }

    private void remove(UUID id) {
        List<String> chunkIds = repository.chunkIds(id);
        if (!chunkIds.isEmpty()) vectors.delete(chunkIds);
        repository.delete(id);
    }

    public static String normalizeCategory(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 80)
            throw new IllegalArgumentException("카테고리는 1~80자여야 합니다");
        return value.trim();
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private static List<Document> extractHtml(byte[] bytes) {
        var html = Jsoup.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        html.select("nav,footer,header,aside,script,style,form").remove();
        var root = html.selectFirst("main");
        if (root == null) root = html.selectFirst("article");
        if (root == null) root = html.body();
        List<Document> sections = new ArrayList<>();
        String section = "";
        var text = new StringBuilder();
        for (var element : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td")) {
            if (element.tagName().matches("h[1-6]")) {
                if (!text.isEmpty()) sections.add(new Document(text.toString(), Map.of("section", section)));
                section = element.text();
                text = new StringBuilder(section);
            } else if (!element.text().isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(element.text());
            }
        }
        if (!text.isEmpty()) sections.add(new Document(text.toString(), Map.of("section", section)));
        return sections;
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
