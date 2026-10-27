package dev.springrag;

import dev.springrag.dao.KnowledgeDao;
import dev.springrag.service.KnowledgeService;
import dev.springrag.service.OcrClient;
import dev.springrag.service.PdfExtractionClient;
import dev.springrag.dto.PdfPageDto;
import dev.springrag.dto.PdfTableRowDto;

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
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class KnowledgeExtractionTest {
    @TempDir Path tempDir;

    @Test void pdfIndexesBodyAndTableRowSeparately() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        PdfExtractionClient pdf = bytes -> List.of(new PdfPageDto(2, "본문 근거", false,
            List.of(new PdfTableRowDto(1, 1, List.of("직무", "근무지"), List.of("개발", "서울"),
                "| 직무 | 근무지 |\n| --- | --- |\n| 개발 | 서울 |", "COMPLETE"))));
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        service.upload(new MockMultipartFile("file", "roles.pdf", "application/pdf", new byte[] {1}), "채용", "alice", false);

        assertEquals(2, added.size(), added.toString());
        assertEquals("BODY", added.get(0).getMetadata().get("contentType"));
        assertEquals("TABLE_ROW", added.get(1).getMetadata().get("contentType"));
        assertEquals(2, added.get(1).getMetadata().get("page_number"));
        assertEquals(1, added.get(1).getMetadata().get("tableIndex"));
        assertEquals(1, added.get(1).getMetadata().get("rowIndex"));
        assertEquals("COMPLETE", added.get(1).getMetadata().get("tableExtractionStatus"));
        assertTrue(added.get(1).getText().contains("| 개발 | 서울 |"));
        assertFalse(added.get(0).getText().contains("개발"));
    }

    @Test void longPdfTableRowStaysWithinTokenLimit() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        String cell = IntStream.range(0, 1500).mapToObj(i -> "term" + i).collect(Collectors.joining(" "));
        String markdown = "| description |\n| --- |\n| " + cell + " |";
        PdfExtractionClient pdf = bytes -> List.of(new PdfPageDto(1, "", false,
            List.of(new PdfTableRowDto(1, 1, List.of("description"), List.of(cell), markdown, "COMPLETE"))));
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        service.upload(new MockMultipartFile("file", "table.pdf", "application/pdf", new byte[] {1}), "Guide", "alice", false);

        assertTrue(added.size() > 1);
        var counter = new JTokkitTokenCountEstimator();
        assertTrue(added.stream().allMatch(chunk -> counter.estimate(chunk.getText()) <= 800));
        assertTrue(added.stream().allMatch(chunk -> "TABLE_ROW".equals(chunk.getMetadata().get("contentType"))));
        assertTrue(added.stream().allMatch(chunk -> chunk.getText().startsWith("페이지 1 · 표 1 · 행 1\n| 항목 | 내용 |")));
        assertTrue(added.stream().allMatch(chunk -> chunk.getText().contains("| description |")));
    }

    @Test @DisplayName("긴 PDF 표 열 제목도 유실 없이 800토큰 청크로 등록한다")
    void longPdfTableHeaderIsSplitWithoutDroppingCell() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        String header = IntStream.range(0, 1200).mapToObj(i -> "heading" + i).collect(Collectors.joining(" "));
        String markdown = "| " + header + " |\n| --- |\n| 승인 기준 |";
        PdfExtractionClient pdf = bytes -> List.of(new PdfPageDto(1, "", false,
            List.of(new PdfTableRowDto(1, 1, List.of(header), List.of("승인 기준"), markdown, "COMPLETE"))));
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        service.upload(new MockMultipartFile("file", "table.pdf", "application/pdf", new byte[] {1}), "Guide", "alice", false);

        assertTrue(added.size() > 1);
        var counter = new JTokkitTokenCountEstimator();
        assertTrue(added.stream().allMatch(chunk -> counter.estimate(chunk.getText()) <= 800));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("heading0")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("heading1199")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("승인 기준")));
        assertTrue(added.stream().allMatch(chunk -> "TABLE_ROW".equals(chunk.getMetadata().get("contentType"))));
    }

    @Test @DisplayName("같은 PDF 표의 긴 열 제목은 여러 행에서도 한 번만 색인한다")
    void longPdfTableHeaderIsIndexedOncePerTable() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        String header = IntStream.range(0, 1200).mapToObj(i -> "heading" + i).collect(Collectors.joining(" "));
        List<PdfTableRowDto> rows = IntStream.rangeClosed(1, 3)
            .mapToObj(i -> new PdfTableRowDto(1, i, List.of(header), List.of("값" + i),
                "| " + header + " |\n| --- |\n| 값" + i + " |", "COMPLETE"))
            .toList();
        PdfExtractionClient pdf = bytes -> List.of(new PdfPageDto(1, "", false, rows));
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        service.upload(new MockMultipartFile("file", "table.pdf", "application/pdf", new byte[] {1}), "Guide", "alice", false);

        assertEquals(1, added.stream().filter(chunk -> chunk.getText().contains("heading1199")).count());
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("값1")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("값2")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("값3")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("| 열 1 제목 |")));
        assertTrue(added.stream().anyMatch(chunk -> chunk.getText().contains("| 열 1 내용 |")));
        assertTrue(added.stream().filter(chunk -> chunk.getText().contains("| 열 1 제목 |"))
            .allMatch(chunk -> "HEADER".equals(chunk.getMetadata().get("tablePart"))));
        assertTrue(added.stream().filter(chunk -> chunk.getText().contains("| 열 1 내용 |"))
            .allMatch(chunk -> "CELL".equals(chunk.getMetadata().get("tablePart"))
                && Integer.valueOf(1).equals(chunk.getMetadata().get("columnIndex"))));
    }

    @Test @DisplayName("행 전체가 800토큰 이하라도 긴 열 제목은 표에서 한 번만 색인한다")
    void nearLimitTableHeaderIsNotRepeated() throws Exception {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        String header = "word ".repeat(740);
        List<PdfTableRowDto> rows = IntStream.rangeClosed(1, 3)
            .mapToObj(i -> new PdfTableRowDto(1, i, List.of(header), List.of("값" + i),
                "| " + header + " |\n| --- |\n| 값" + i + " |", "COMPLETE"))
            .toList();
        var counter = new JTokkitTokenCountEstimator();
        assertTrue(counter.estimate(rows.get(0).markdown()) <= 800);
        assertTrue(counter.estimate("페이지 1 · 표 1 · 행 1\n| 항목 | 내용 |\n| --- | --- |\n| " + header + " | ") > 700);
        PdfExtractionClient pdf = bytes -> List.of(new PdfPageDto(1, "", false, rows));
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        service.upload(new MockMultipartFile("file", "table.pdf", "application/pdf", new byte[] {1}), "Guide", "alice", false);

        assertEquals(1, added.stream().filter(chunk -> "HEADER".equals(chunk.getMetadata().get("tablePart"))).count());
        assertEquals(3, added.stream().filter(chunk -> "CELL".equals(chunk.getMetadata().get("tablePart"))).count());
    }

    @Test void pdfExtractionFailureKeepsExistingIndex() {
        List<Document> added = new ArrayList<>();
        UUID oldId = UUID.randomUUID();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return new Existing(oldId, "old-hash", "Guide"); }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {
                throw new AssertionError("catalogue must remain untouched");
            }
        };
        PdfExtractionClient pdf = bytes -> { throw new PdfExtractionClient.PdfExtractionUnavailableException("unavailable", null); };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(),
            tempDir.toString(), image -> { throw new AssertionError("OCR must not run"); }, pdf);

        assertThrows(PdfExtractionClient.PdfExtractionUnavailableException.class,
            () -> service.ingestUrl(new byte[] {1}, "old.pdf", "https://docs.example.org/old.pdf", "Guide"));
        assertTrue(added.isEmpty());
    }

    @Test void longTextChunksOverlapWithoutExceedingTokenBudget() {
        List<Document> added = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return null; }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString());
        String body = IntStream.range(0, 900).mapToObj(i -> "term" + i).collect(Collectors.joining(" "));

        service.ingestUrl(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "terms.txt", "https://docs.example.org/terms.txt", "Guide");

        assertTrue(added.size() >= 2);
        var tokens = new JTokkitTokenCountEstimator();
        assertTrue(added.stream().allMatch(chunk -> tokens.estimate(chunk.getText()) <= 800));
        String first = added.get(0).getText();
        String second = added.get(1).getText();
        int sharedLength = IntStream.range(0, first.length())
            .filter(i -> second.startsWith(first.substring(i)))
            .map(i -> first.length() - i)
            .max().orElse(0);
        assertTrue(tokens.estimate(first.substring(first.length() - sharedLength)) >= 90);
    }

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

    @Test void htmlNestedListParagraphAppearsOnceInIndexedText() throws Exception {
        List<Document> added = ingestDuplicateHtml();

        String dependencies = added.stream().filter(doc -> "Dependencies".equals(doc.getMetadata().get("section")))
            .findFirst().orElseThrow().getText();
        assertEquals(1, dependencies.split("\\bMaven\\b", -1).length - 1);
    }

    @Test void htmlRepeatedIdenticalSectionsProduceOneChunk() throws Exception {
        List<Document> added = ingestDuplicateHtml();

        assertEquals(1, added.stream().filter(doc -> "Dependencies".equals(doc.getMetadata().get("section"))).count());
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("PagePdfDocumentReader extracts PDF pages.")));
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("ParagraphPdfDocumentReader extracts PDF paragraphs.")));
    }

    private List<Document> ingestDuplicateHtml() throws Exception {
        List<Document> added = new ArrayList<>();
        KnowledgeDao repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return null; }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString());
        service.ingestUrl(Files.readAllBytes(Path.of("src/test/resources/etl/duplicate-pdf-dependencies.html")),
            "ETL", "https://docs.example.org/etl.html", "Spring AI");
        return added;
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
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString(),
            image -> { throw new AssertionError("OCR must not run"); },
            bytes -> List.of(new PdfPageDto(1, "PDF source evidence", false, List.of())));
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

    @Test void scannedPdfPageUsesOcrWithoutReprocessingTextPage() throws Exception {
        List<Document> added = new ArrayList<>();
        List<byte[]> ocrImages = new ArrayList<>();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {}
        };
        OcrClient ocr = image -> { ocrImages.add(image); return "스캔된 문서 근거"; };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString(), ocr,
            bytes -> List.of(new PdfPageDto(1, "Native PDF evidence", false, List.of()),
                new PdfPageDto(2, "", true, List.of())));
        byte[] pdf;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var textPage = new PDPage();
            document.addPage(textPage);
            try (var content = new PDPageContentStream(document, textPage)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Native PDF evidence");
                content.endText();
            }
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        service.upload(new MockMultipartFile("file", "mixed.pdf", "application/pdf", pdf), "Guide", "alice", false);

        assertEquals(1, ocrImages.size());
        assertTrue(ocrImages.get(0).length > 0);
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("Native PDF evidence")));
        assertTrue(added.stream().anyMatch(doc -> doc.getText().contains("스캔된 문서 근거")
            && Integer.valueOf(2).equals(doc.getMetadata().get("page_number"))));
    }

    @Test void ocrFailureLeavesExistingUrlIndexUntouched() throws Exception {
        List<Document> added = new ArrayList<>();
        UUID oldId = UUID.randomUUID();
        var repository = new KnowledgeDao(new JdbcTemplate()) {
            @Override public Existing findUrl(String scope, String url) { return new Existing(oldId, "old-hash", "Guide"); }
            @Override public void save(UUID id, boolean existing, String scope, String owner, String title, String url,
                                String path, String hash, String category, List<String> chunkIds) {
                throw new AssertionError("catalogue must remain untouched");
            }
        };
        var service = new KnowledgeService(repository, vectors(added, new ArrayList<>()), transaction(), tempDir.toString(),
            image -> { throw new IllegalStateException("OCR unavailable"); },
            bytes -> List.of(new PdfPageDto(1, "", true, List.of())));
        byte[] pdf;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            pdf = output.toByteArray();
        }

        assertThrows(IllegalStateException.class, () -> service.ingestUrl(pdf, "scan.pdf", "https://docs.example.org/scan.pdf", "Guide"));
        assertTrue(added.isEmpty());
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
