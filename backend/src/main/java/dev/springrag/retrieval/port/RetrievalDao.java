package dev.springrag.retrieval.port;

import java.util.List;
import org.springframework.ai.document.Document;

/** BM25 후보와 같은 문서의 근거 청크를 권한 범위 안에서 조회하는 계약이다. */
public interface RetrievalDao {
    /**
     * 범위·소유권을 적용한 BM25 후보를 조회한다.
     * @param query 검색어
     * @param scope 문서 범위
     * @param owner 개인 문서 소유자
     * @param limit 최대 후보 수
     * @return BM25 순위의 접근 가능한 청크
     */
    List<Document> bm25(String query, String scope, String owner, int limit);

    /**
     * 같은 문서에서 passage를 구성할 이웃 청크를 조회한다.
     * @param anchor 기준 청크
     * @param scope 문서 범위
     * @param owner 개인 문서 소유자
     * @return 접근 가능한 인접·표 제목 청크
     */
    List<Document> neighbors(Document anchor, String scope, String owner);
}
