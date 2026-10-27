package dev.springrag.infrastructure;

import dev.springrag.common.port.VectorIndex;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

/** Spring AI 벡터 저장소를 문서·검색 모듈의 공통 포트에 연결한다. */
@Component
public class SpringAiVectorIndex implements VectorIndex {
    private final VectorStore store;

    /**
     * 실제 벡터 저장소를 연결한다.
     * @param store Spring AI 벡터 저장소
     */
    public SpringAiVectorIndex(VectorStore store) {
        this.store = store;
    }

    @Override
    public void add(List<Document> documents) {
        store.add(documents);
    }

    @Override
    public void delete(List<String> ids) {
        store.delete(ids);
    }

    @Override
    public List<Document> search(String query, int topK, double threshold, String filter) {
        return store.similaritySearch(SearchRequest.builder().query(query).topK(topK)
            .similarityThreshold(threshold).filterExpression(filter).build());
    }
}
