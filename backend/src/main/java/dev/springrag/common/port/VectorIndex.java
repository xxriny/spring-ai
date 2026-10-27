package dev.springrag.common.port;

import java.util.List;
import org.springframework.ai.document.Document;

/** 문서 적재와 권한 필터를 적용한 의미 검색에 사용하는 벡터 저장소 경계다. */
public interface VectorIndex {
    /**
     * 검색할 문서 청크를 적재한다.
     * @param documents 적재할 청크
     */
    void add(List<Document> documents);

    /**
     * 등록된 청크를 식별자로 삭제한다.
     * @param ids 삭제할 청크 식별자
     */
    void delete(List<String> ids);

    /**
     * 권한 필터 안에서 의미 유사도가 높은 청크를 조회한다.
     * @param query 검색 질의
     * @param topK 최대 결과 수
     * @param threshold 최소 유사도
     * @param filter 권한 범위를 제한하는 Spring AI 필터 표현식
     * @return 유사도 순서의 청크
     */
    List<Document> search(String query, int topK, double threshold, String filter);
}
