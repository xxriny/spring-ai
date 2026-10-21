package dev.springrag.service;

import dev.springrag.dto.SearchHitDto;
import dev.springrag.dto.SearchResultDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private final VectorStore vectors;
    private final Reranker reranker;

    public RetrievalService(VectorStore vectors, Reranker reranker) {
        this.vectors = vectors;
        this.reranker = reranker;
    }

    public SearchResultDto search(String question, String scope, String owner) {
        if (question == null || question.isBlank() || question.length() > 4000)
            throw new IllegalArgumentException("질문은 1~4000자여야 합니다");
        if (!"SHARED".equals(scope) && !"PRIVATE".equals(scope)) throw new IllegalArgumentException("범위가 올바르지 않습니다");
        if ("PRIVATE".equals(scope) && (owner == null || !owner.matches("[A-Za-z0-9._-]{1,100}")))
            throw new IllegalArgumentException("사용자 ID가 올바르지 않습니다");
        String filter = "PRIVATE".equals(scope) ? "scope == 'PRIVATE' && ownerId == '" + owner + "'" : "scope == 'SHARED'";
        var docs = vectors.similaritySearch(SearchRequest.builder().query(question.trim()).topK(10)
            .similarityThreshold(0.3).filterExpression(filter).build());
        List<SearchHitDto> candidates = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) candidates.add(hit(docs.get(i), i + 1, null));
        if (docs.isEmpty()) return new SearchResultDto(candidates, List.of());
        List<Double> scores = reranker.scores(question, docs.stream().map(Document::getText).toList());
        if (scores.size() != docs.size() || scores.stream().anyMatch(s -> s == null || !Double.isFinite(s)))
            throw new Reranker.RerankerUnavailableException("리랭커 점수가 올바르지 않습니다", null);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> scores.get(i)).reversed());
        List<SearchHitDto> reranked = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) reranked.add(hit(docs.get(order.get(i)), i + 1, scores.get(order.get(i))));
        return new SearchResultDto(candidates, reranked);
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
