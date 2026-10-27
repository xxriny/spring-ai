package dev.springrag.retrieval.port;

import java.util.List;

/** 검색 후보를 모델 점수로 재정렬하는 외부 호출 계약이다. */
public interface Reranker {
    /**
     * @param query 검색어
     * @param passages RRF 상위 후보 본문
     * @return 입력 순서와 같은 길이의 재정렬 점수
     * @throws RerankerUnavailableException 리랭커를 사용할 수 없는 경우
     */
    List<Double> scores(String query, List<String> passages);

    /** 리랭커 장애를 표시한다. */
    class RerankerUnavailableException extends RuntimeException {
        /** @param message 내부 오류 설명 @param cause 원인 예외 */
        public RerankerUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
}
