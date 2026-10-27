package dev.springrag.knowledge.service;

import dev.springrag.knowledge.port.KnowledgeRepository;
import dev.springrag.common.config.LocalEmbeddingConfig;
import dev.springrag.knowledge.dto.DocumentDto;
import dev.springrag.knowledge.dto.PdfPageDto;
import dev.springrag.knowledge.dto.PdfTableRowDto;
import dev.springrag.knowledge.port.OcrClient;
import dev.springrag.knowledge.port.PdfExtractionClient;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import dev.springrag.common.port.VectorIndex;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.jsoup.Jsoup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import javax.imageio.ImageIO;

@Service
public class KnowledgeService {
    private final KnowledgeRepository repository;
    private final VectorIndex vectors;
    private final TransactionTemplate transaction;
    private final Path uploadDir;
    private final OcrClient ocr;
    private final PdfExtractionClient pdfExtraction;
    private final TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(700).build();
    private final JTokkitTokenCountEstimator tokenCounter = new JTokkitTokenCountEstimator();

    /**
     * 문서 카탈로그와 추출·색인 어댑터를 연결한다.
     * @param repository 문서 카탈로그 저장 포트
     * @param vectors 벡터 색인 포트
     * @param transaction 카탈로그 변경 트랜잭션
     * @param uploadDir 원본 파일 저장 경로
     * @param ocr 스캔 PDF의 OCR 포트
     * @param pdfExtraction PDF 본문·표 추출 포트
     */
    @Autowired public KnowledgeService(KnowledgeRepository repository, VectorIndex vectors, TransactionTemplate transaction,
                     @Value("${app.upload-dir}") String uploadDir, OcrClient ocr, PdfExtractionClient pdfExtraction) {
        this.repository = repository;
        this.vectors = vectors;
        this.transaction = transaction;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath();
        this.ocr = ocr;
        this.pdfExtraction = pdfExtraction;
    }

    /**
     * PDF 추출을 사용하지 않는 호출자를 위해 문서 색인 서비스를 연결한다.
     * @param repository 문서 카탈로그 저장 포트
     * @param vectors 벡터 색인 포트
     * @param transaction 카탈로그 변경 트랜잭션
     * @param uploadDir 원본 파일 저장 경로
     * @param ocr OCR 포트
     */
    public KnowledgeService(KnowledgeRepository repository, VectorIndex vectors, TransactionTemplate transaction,
                            String uploadDir, OcrClient ocr) {
        this(repository, vectors, transaction, uploadDir, ocr,
            bytes -> { throw new PdfExtractionClient.PdfExtractionUnavailableException("PDF 추출이 설정되지 않았습니다", null); });
    }

    /**
     * 외부 추출 서비스를 사용하지 않는 호출자를 위해 문서 색인 서비스를 연결한다.
     * @param repository 문서 카탈로그 저장 포트
     * @param vectors 벡터 색인 포트
     * @param transaction 카탈로그 변경 트랜잭션
     * @param uploadDir 원본 파일 저장 경로
     */
    public KnowledgeService(KnowledgeRepository repository, VectorIndex vectors, TransactionTemplate transaction, String uploadDir) {
        this(repository, vectors, transaction, uploadDir, image -> { throw new OcrClient.OcrUnavailableException("OCR이 설정되지 않았습니다", null); });
    }

    public List<DocumentDto> list(String owner, String scope) {
        return repository.list(owner, scope);
    }

    /**
     * 파일에서 본문과 표를 추출해 권한 범위에 맞는 검색 청크로 등록한다.
     *
     * @param file 등록할 파일
     * @param category 문서 카테고리
     * @param owner 로그인 계정
     * @param admin 관리자 여부; 관리자는 공용 문서로 등록한다
     * @return 등록된 문서 정보
     * @throws IOException 원본 파일을 읽거나 저장할 수 없는 경우
     * @throws IllegalArgumentException 파일 형식 또는 추출 결과가 올바르지 않은 경우
     * @throws PdfExtractionClient.PdfExtractionUnavailableException PDF 추출 서버를 사용할 수 없는 경우
     */
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
            case "pdf" -> extractPdf(bytes);
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
            metadata.put("embeddingModel", LocalEmbeddingConfig.EMBEDDING_ID);
            String previous = null;
            List<Document> pageChunks = "TABLE_ROW".equals(metadata.get("contentType"))
                ? List.of(new Document(page.getText(), metadata))
                : tokenCounter.estimate(page.getText()) <= 700
                    ? List.of(new Document(page.getText(), metadata))
                    : splitter.apply(List.of(new Document(page.getText(), metadata)));
            for (Document chunk : pageChunks) {
                String content = chunk.getText();
                if (previous != null && !"TABLE_ROW".equals(metadata.get("contentType"))) content = prependOverlap(previous, content);
                var ordered = new java.util.HashMap<>(chunk.getMetadata());
                ordered.put("chunkOrder", chunks.size());
                chunks.add(new Document(content, ordered));
                previous = chunk.getText();
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

    private String prependOverlap(String previous, String current) {
        int low = 0;
        int high = previous.codePointCount(0, previous.length());
        while (low < high) {
            int mid = (low + high) / 2;
            String suffix = previous.substring(previous.offsetByCodePoints(0, mid));
            if (tokenCounter.estimate(suffix) > 100) low = mid + 1;
            else high = mid;
        }
        String overlap = previous.substring(previous.offsetByCodePoints(0, low));
        String combined = overlap + "\n" + current;
        while (tokenCounter.estimate(combined) > 800 && !overlap.isEmpty()) {
            overlap = overlap.substring(overlap.offsetByCodePoints(0, 1));
            combined = overlap + "\n" + current;
        }
        return combined;
    }

    private static List<Document> extractHtml(byte[] bytes) {
        var html = Jsoup.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        html.select("nav,footer,header,aside,script,style,form").remove();
        var root = html.selectFirst("main");
        if (root == null) root = html.selectFirst("article");
        if (root == null) root = html.body();
        Map<String, Document> sections = new LinkedHashMap<>();
        String section = "";
        var text = new StringBuilder();
        for (var element : root.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,td")) {
            if (element.tagName().matches("h[1-6]")) {
                if (!text.isEmpty()) sections.putIfAbsent(section + "\0" + text,
                    new Document(text.toString(), Map.of("section", section)));
                section = element.text();
                text = new StringBuilder(section);
            } else if (element.parents().stream().anyMatch(parent -> parent.is("p,li,pre,blockquote,td"))) {
                continue;
            } else if (!element.text().isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(element.text());
            }
        }
        if (!text.isEmpty()) sections.putIfAbsent(section + "\0" + text,
            new Document(text.toString(), Map.of("section", section)));
        return new ArrayList<>(sections.values());
    }

    private List<Document> extractPdf(byte[] bytes) {
        List<PdfPageDto> extracted = pdfExtraction.extract(bytes);
        List<Document> pages = new ArrayList<>();
        boolean needsOcr = extracted.stream().anyMatch(PdfPageDto::ocrRequired);
        try (var pdf = needsOcr ? Loader.loadPDF(bytes) : null) {
            PDFRenderer renderer = needsOcr ? new PDFRenderer(pdf) : null;
            for (PdfPageDto page : extracted) {
                Set<String> indexedHeaders = new HashSet<>();
                String body = page.body();
                if (page.ocrRequired()) {
                    var image = renderer.renderImageWithDPI(page.pageNumber() - 1, 150);
                    try (var output = new ByteArrayOutputStream()) {
                        ImageIO.write(image, "png", output);
                        body = ocr.recognize(output.toByteArray());
                    }
                }
                if (!body.isBlank()) pages.add(new Document(body, Map.of("page_number", page.pageNumber(), "contentType", "BODY")));
                for (PdfTableRowDto row : page.tables()) {
                    Map<String, Object> metadata = Map.of("page_number", page.pageNumber(),
                        "contentType", "TABLE_ROW", "tableIndex", row.tableIndex(), "rowIndex", row.rowIndex(),
                        "tableExtractionStatus", row.extractionStatus());
                    pages.addAll(tableChunks(row, page.pageNumber(), metadata, indexedHeaders));
                }
            }
            return pages;
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF를 읽을 수 없습니다", e);
        }
    }

    private List<Document> tableChunks(PdfTableRowDto row, int pageNumber, Map<String, Object> metadata,
                                       Set<String> indexedHeaders) {
        String identity = "페이지 " + pageNumber + " · 표 " + row.tableIndex() + " · 행 " + row.rowIndex() + "\n";
        if (tokenCounter.estimate(row.markdown()) <= 800) {
            boolean longHeader = false;
            for (int column = 0; column < row.cells().size(); column++) {
                if (!row.cells().get(column).isBlank() && tokenCounter.estimate(identity
                    + "| 항목 | 내용 |\n| --- | --- |\n| " + escapeTableCell(row.headers().get(column)) + " | ") > 700) {
                    longHeader = true;
                    break;
                }
            }
            if (!longHeader) return List.of(new Document(row.markdown(), metadata));
        }
        List<Document> chunks = new ArrayList<>();
        for (int column = 0; column < row.cells().size(); column++) {
            String cell = row.cells().get(column);
            if (cell.isBlank()) continue;
            String prefix = identity + "| 항목 | 내용 |\n| --- | --- |\n| " + escapeTableCell(row.headers().get(column)) + " | ";
            if (tokenCounter.estimate(prefix) > 700) {
                // 같은 표의 긴 제목은 한 번만 임베딩하고 이후 행은 열 번호로 연결한다.
                Map<String, Object> headerMetadata = new HashMap<>(metadata);
                headerMetadata.put("columnIndex", column + 1);
                headerMetadata.put("tablePart", "HEADER");
                if (indexedHeaders.add(row.tableIndex() + ":" + column))
                    addTableTextChunks(chunks, identity + "| 항목 | 내용 |\n| --- | --- |\n| 열 " + (column + 1) + " 제목 | ",
                        row.headers().get(column), headerMetadata);
                prefix = identity + "| 항목 | 내용 |\n| --- | --- |\n| 열 " + (column + 1) + " 내용 | ";
                Map<String, Object> cellMetadata = new HashMap<>(metadata);
                cellMetadata.put("columnIndex", column + 1);
                cellMetadata.put("tablePart", "CELL");
                addTableTextChunks(chunks, prefix, cell, cellMetadata);
            } else {
                addTableTextChunks(chunks, prefix, cell, metadata);
            }
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("PDF 표 행을 청크로 나눌 수 없습니다");
        return chunks;
    }

    private void addTableTextChunks(List<Document> chunks, String prefix, String value, Map<String, Object> metadata) {
        int codePoints = value.codePointCount(0, value.length());
        int start = 0;
        while (start < codePoints) {
            int low = start + 1;
            int high = codePoints;
            while (low < high) {
                int mid = (low + high + 1) / 2;
                String content = escapeTableCell(value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, mid)));
                if (tokenCounter.estimate(prefix + content + " |") <= 800) low = mid;
                else high = mid - 1;
            }
            String content = escapeTableCell(value.substring(value.offsetByCodePoints(0, start), value.offsetByCodePoints(0, low)));
            chunks.add(new Document(prefix + content + " |", metadata));
            start = low;
        }
    }

    private static String escapeTableCell(String value) {
        return value.replace("|", "\\|").replace("\n", "<br>");
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
