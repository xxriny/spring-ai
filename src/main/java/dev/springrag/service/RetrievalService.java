package dev.springrag.service;

import dev.springrag.dao.RetrievalDao;
import dev.springrag.dto.SearchHitDto;
import dev.springrag.dto.SearchResultDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int TOP_K = 10;
    private static final int PASSAGE_K = 3;
    private static final int RRF_K = 60;
    private final VectorStore vectors;
    private final Reranker reranker;
    private final RetrievalDao dao;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator();

    public RetrievalService(VectorStore vectors, Reranker reranker, RetrievalDao dao) {
        this.vectors = vectors;
        this.reranker = reranker;
        this.dao = dao;
    }

    public SearchResultDto search(String question, String scope, String owner) {
        if (question == null || question.isBlank() || question.length() > 4000)
            throw new IllegalArgumentException("질문은 1~4000자여야 합니다");
        if (!"SHARED".equals(scope) && !"PRIVATE".equals(scope)) throw new IllegalArgumentException("범위가 올바르지 않습니다");
        if ("PRIVATE".equals(scope) && (owner == null || !owner.matches("[A-Za-z0-9._-]{1,100}")))
            throw new IllegalArgumentException("사용자 ID가 올바르지 않습니다");
        String query = question.trim();
        String filter = "PRIVATE".equals(scope) ? "scope == 'PRIVATE' && ownerId == '" + owner + "'" : "scope == 'SHARED'";
        long start = System.nanoTime();
        List<Document> semantic = vectors.similaritySearch(SearchRequest.builder().query(query).topK(TOP_K)
            .similarityThreshold(0.3).filterExpression(filter).build());
        long vectorDone = System.nanoTime();
        List<Document> lexical = dao.bm25(query, scope, owner, TOP_K);
        long bm25Done = System.nanoTime();
        List<Document> fused = fuse(semantic, lexical);
        List<SearchHitDto> vectorHits = hits(semantic);
        List<SearchHitDto> bm25Hits = hits(lexical);
        List<SearchHitDto> candidates = hits(fused);
        if (fused.isEmpty()) {
            logSearch(scope, semantic.size(), lexical.size(), 0, 0, start, vectorDone, bm25Done, bm25Done, bm25Done);
            return new SearchResultDto(vectorHits, bm25Hits, candidates, List.of(), List.of());
        }
        List<Double> scores = reranker.scores(query, fused.stream().map(Document::getText).toList());
        long rerankDone = System.nanoTime();
        if (scores.size() != fused.size() || scores.stream().anyMatch(s -> s == null || !Double.isFinite(s)))
            throw new Reranker.RerankerUnavailableException("리랭커 점수가 올바르지 않습니다", null);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < fused.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed());
        List<SearchHitDto> reranked = new ArrayList<>();
        List<SearchHitDto> passages = new ArrayList<>();
        for (int rank = 0; rank < order.size(); rank++) {
            int index = order.get(rank);
            Document anchor = fused.get(index);
            reranked.add(hit(anchor, rank + 1, scores.get(index)));
            if (rank < PASSAGE_K) passages.add(passage(anchor, rank + 1, scores.get(index), scope, owner));
        }
        logSearch(scope, semantic.size(), lexical.size(), fused.size(), passages.size(),
            start, vectorDone, bm25Done, rerankDone, System.nanoTime());
        return new SearchResultDto(vectorHits, bm25Hits, candidates, reranked, passages);
    }

    private static void logSearch(String scope, int vectorCount, int bm25Count, int fusedCount, int passageCount,
                                  long start, long vectorDone, long bm25Done, long rerankDone, long end) {
        log.atInfo().addKeyValue("scope", scope).addKeyValue("vectorCount", vectorCount)
            .addKeyValue("bm25Count", bm25Count).addKeyValue("fusedCount", fusedCount)
            .addKeyValue("passageCount", passageCount)
            .addKeyValue("vectorMs", (vectorDone - start) / 1_000_000)
            .addKeyValue("bm25Ms", (bm25Done - vectorDone) / 1_000_000)
            .addKeyValue("rerankMs", (rerankDone - bm25Done) / 1_000_000)
            .addKeyValue("passageMs", (end - rerankDone) / 1_000_000)
            .log("retrieval_completed");
    }

    private static List<Document> fuse(List<Document> semantic, List<Document> lexical) {
        Map<String, Document> byId = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        addRanks(semantic, byId, scores);
        addRanks(lexical, byId, scores);
        return byId.keySet().stream().sorted(Comparator.<String>comparingDouble(scores::get).reversed())
            .limit(TOP_K).map(byId::get).toList();
    }

    private static void addRanks(List<Document> docs, Map<String, Document> byId, Map<String, Double> scores) {
        for (int i = 0; i < docs.size(); i++) {
            Document doc = docs.get(i);
            byId.putIfAbsent(doc.getId(), doc);
            scores.merge(doc.getId(), 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    private SearchHitDto passage(Document anchor, int rank, double score, String scope, String owner) {
        List<Document> context = new ArrayList<>();
        for (Document neighbor : dao.neighbors(anchor, scope, owner)) {
            if (sameBoundary(anchor, neighbor)) context.add(neighbor);
        }
        context.add(anchor);
        context.sort(Comparator.comparingInt(d -> chunkOrder(d.getMetadata())));
        String content = "";
        List<String> sourceIds = new ArrayList<>();
        for (Document chunk : context) {
            String text = chunk.getText();
            if (chunkOrder(chunk.getMetadata()) < chunkOrder(anchor.getMetadata())) text = tail(text, 200);
            if (chunkOrder(chunk.getMetadata()) > chunkOrder(anchor.getMetadata())) text = head(text, 200);
            content = "TABLE_ROW".equals(anchor.getMetadata().get("contentType"))
                ? content + (content.isEmpty() ? "" : "\n") + text
                : joinWithoutOverlap(content, text);
            sourceIds.add(chunk.getId());
        }
        SearchHitDto original = hit(anchor, rank, score);
        return new SearchHitDto(original.chunkId(), original.documentId(), original.title(), original.url(),
            original.section(), original.category(), head(content, 1200), original.page(), rank,
            original.vectorScore(), score, sourceIds);
    }

    private static boolean sameBoundary(Document anchor, Document neighbor) {
        Map<String, Object> a = anchor.getMetadata();
        Map<String, Object> b = neighbor.getMetadata();
        if (!String.valueOf(a.get("documentId")).equals(String.valueOf(b.get("documentId")))) return false;
        if (!String.valueOf(a.get("contentType")).equals(String.valueOf(b.get("contentType")))) return false;
        if (!Objects.equals(a.get("section"), b.get("section"))) return false;
        if (!Objects.equals(a.get("page_number"), b.get("page_number"))) return false;
        if ("TABLE_ROW".equals(a.get("contentType")) && !String.valueOf(a.get("tableIndex")).equals(String.valueOf(b.get("tableIndex")))) return false;
        return Math.abs(chunkOrder(a) - chunkOrder(b)) == 1;
    }

    private static int chunkOrder(Map<String, Object> metadata) {
        Object value = metadata.get("chunkOrder");
        return value instanceof Number number ? number.intValue() : value == null ? 0 : Integer.parseInt(value.toString());
    }

    private String head(String text, int limit) {
        if (tokens.estimate(text) <= limit) return text;
        int low = 0, high = text.codePointCount(0, text.length());
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (tokens.estimate(text.substring(0, text.offsetByCodePoints(0, mid))) <= limit) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, text.offsetByCodePoints(0, low));
    }

    private String tail(String text, int limit) {
        if (tokens.estimate(text) <= limit) return text;
        int low = 0, high = text.codePointCount(0, text.length());
        int total = high;
        while (low < high) {
            int mid = (low + high + 1) / 2;
            if (tokens.estimate(text.substring(text.offsetByCodePoints(0, total - mid))) <= limit) low = mid;
            else high = mid - 1;
        }
        return text.substring(text.offsetByCodePoints(0, total - low));
    }

    private static String joinWithoutOverlap(String before, String after) {
        if (before.isEmpty()) return after;
        int overlap = 0;
        int max = Math.min(before.length(), after.length());
        for (int size = 1; size <= max; size++) {
            if (before.regionMatches(before.length() - size, after, 0, size)) overlap = size;
        }
        return before + (overlap == 0 ? "\n" : "") + after.substring(overlap);
    }

    private static List<SearchHitDto> hits(List<Document> docs) {
        List<SearchHitDto> result = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) result.add(hit(docs.get(i), i + 1, null));
        return result;
    }

    private static SearchHitDto hit(Document doc, int rank, Double rerankerScore) {
        var metadata = doc.getMetadata();
        return new SearchHitDto(doc.getId(), String.valueOf(metadata.getOrDefault("documentId", "")),
            String.valueOf(metadata.getOrDefault("title", "문서")), String.valueOf(metadata.getOrDefault("source", "")),
            String.valueOf(metadata.getOrDefault("section", "")), String.valueOf(metadata.getOrDefault("category", "uncategorized")),
            doc.getText(), metadata.getOrDefault("page_number", metadata.get("start_page_number")), rank,
            doc.getScore(), rerankerScore);
    }
}
