package dev.springrag;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.service.KnowledgeService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeExtractionTest {
    @TempDir Path tempDir;

    @Test void htmlDropsNavigationAndKeepsSectionAndOrder() throws Exception {
        List<Document> added = new ArrayList<>();
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return null; }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString());
        byte[] html = Files.readAllBytes(Path.of("src/test/resources/etl/page.html"));
        service.ingestUrl(html, "ETL pipeline", "https://docs.example.org/etl.html", "Spring AI");

        assertFalse(added.isEmpty());
        assertTrue(added.stream().noneMatch(doc -> doc.getText().contains("navigation") || doc.getText().contains("footer links")));
        assertTrue(added.stream().anyMatch(doc -> "Transform".equals(doc.getMetadata().get("section"))));
        assertEquals(List.of(0, 1), added.stream().map(doc -> doc.getMetadata().get("chunkOrder")).distinct().toList());
        assertTrue(added.stream().allMatch(doc -> "https://docs.example.org/etl.html".equals(doc.getMetadata().get("source"))));
    }

    @Test void failedReindexDeletesOnlyNewChunks() {
        List<Document> added = new ArrayList<>();
        List<List<String>> deleted = new ArrayList<>();
        UUID oldId = UUID.randomUUID();
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return new Existing(oldId, "old-hash", "Spring AI"); }
            @Override public List<String> chunkIds(UUID id) { return List.of("old-chunk"); }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) { throw new IllegalStateException("DB unavailable"); }
        };
        var service = new KnowledgeService(repository, vectors(added, deleted), transaction(), tempDir.toString());
        assertThrows(IllegalStateException.class, () -> service.ingestUrl(
            "A new and sufficiently long document body for indexing.".getBytes(), "new", "https://docs.example.org/a.txt", "Spring AI"));
        assertFalse(added.isEmpty());
        assertEquals(added.stream().map(Document::getId).toList(), deleted.get(0));
        assertFalse(deleted.get(0).contains("old-chunk"));
    }

    @Test void textUploadKeepsSourceAndRejectsEmptyExtraction() throws Exception {
        List<Document> added = new ArrayList<>();
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString());
        var file = new MockMultipartFile("file", "guide.txt", "text/plain", "Extract source text".getBytes());
        service.upload(file, "Guide", "alice", false);
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("Extract source text")));
        assertTrue(added.stream().allMatch(doc -> "PRIVATE".equals(doc.getMetadata().get("scope"))
            && "alice".equals(doc.getMetadata().get("ownerId"))
            && "Guide".equals(doc.getMetadata().get("category"))));
        assertThrows(IllegalArgumentException.class, () -> service.upload(
            new MockMultipartFile("file", "empty.txt", "text/plain", "   ".getBytes()), "Guide", "alice", false));
    }

    @Test void partialVectorFailureRemovesOnlyNewChunksBeforeCatalogueSwap() {
        List<Document> added = new ArrayList<>();
        List<List<String>> deleted = new ArrayList<>();
        UUID oldId = UUID.randomUUID();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return new Existing(oldId, "old-hash", "Guide"); }
            @Override public List<String> chunkIds(UUID id) { return List.of("old-chunk"); }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {
                throw new AssertionError("catalogue must remain untouched");
            }
        };
        VectorStore partial = (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(),
            new Class<?>[] {VectorStore.class}, (proxy, method, args) -> {
                if (method.getName().equals("add")) {
                    added.addAll((List<Document>) args[0]);
                    throw new IllegalStateException("partial embedding failure");
                }
                if (method.getName().equals("delete")) { deleted.add((List<String>) args[0]); return null; }
                throw new AssertionError(method.getName());
            });
        var service = new KnowledgeService(repository, partial, transaction(), tempDir.toString());
        assertThrows(IllegalStateException.class, () -> service.ingestUrl(
            "new knowledge".getBytes(), "source.txt", "https://docs.example.org/source.txt", "Guide"));
        assertEquals(added.stream().map(Document::getId).toList(), deleted.get(0));
        assertFalse(deleted.get(0).contains("old-chunk"));
    }

    @Test void pdfAndDocxKeepExtractedTextAndPdfPage() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString());
        byte[] pdf;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("PDF source evidence");
                content.endText();
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        service.upload(new MockMultipartFile("file", "source.pdf", "application/pdf", pdf), "Guide", "alice", false);
        assertTrue(added.stream().anyMatch(doc -> doc.getText().replaceAll("\\s+", " ").contains("PDF source evidence")));
        assertTrue(added.stream().anyMatch(doc -> doc.getMetadata().containsKey("page_number")
            || doc.getMetadata().containsKey("start_page_number")));
        added.clear();
        byte[] docx;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Word source evidence");
            document.write(output);
            docx = output.toByteArray();
        }
        service.upload(new MockMultipartFile("file", "source.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx),
            "Guide", "alice", false);
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("Word source evidence")));
    }

    private static VectorStore vectors(List<Document> added, List<List<String>> deleted) {
        return (VectorStore) Proxy.newProxyInstance(VectorStore.class.getClassLoader(), new Class<?>[] { VectorStore.class },
            (proxy, method, args) -> {
                if (method.getName().equals("add")) { added.addAll((List<Document>) args[0]); return null; }
                if (method.getName().equals("delete")) { deleted.add((List<String>) args[0]); return null; }
                throw new AssertionError(method.getName());
            });
    }

    private static TransactionTemplate transaction() {
        return new TransactionTemplate(new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) {}
            @Override public void rollback(TransactionStatus status) {}
        });
    }
}
