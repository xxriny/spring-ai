# Spring AI 답변 품질 평가: 20261027T060441Z

- 상태: 평가 실행 완료
- 질문 세트: 공식 Spring AI 문서 4쪽, 고정 질문 16개 (`evaluation/questions.json`)
- 평가 모델: Ollama `qwen3.5:9b`
- 평가 방식: Spring AI `RelevancyEvaluator`, `FactCheckingEvaluator`, 인용 구조 검사. 판정 기준과 한계는 [평가 방법](../AnswerQualityEvaluation.md)을 따른다.

## 실행 설정

| 항목 | 값 |
| --- | --- |
| 임베딩 | qwen3-embedding:8b/1536 |
| 벡터 후보 Top-k / 유사도 기준 | 10 / 0.3 |
| Sparse 검색 | pg_search 0.26.0 |
| 결합 | RRF k=60 |
| 리랭커 | Qwen/Qwen3-Reranker-0.6B |
| 최종 passage Top-k | 3 |

## 검색 지표

근거 문구가 기대 문서의 청크에 포함됐는지 측정한 결과이며 답변 품질 지표가 아니다.

| 단계 | 근거 문구 Hit@3 | MRR |
| --- | ---: | ---: |
| 벡터 후보 | 14/16 (87.5%) | 0.799 |
| BM25 후보 | 11/16 (68.8%) | 0.578 |
| RRF 결합 | 14/16 (87.5%) | 0.844 |
| Qwen 재정렬 | 14/16 (87.5%) | 0.726 |
| 최종 passage | 14/16 (87.5%) | 0.698 |

## 답변 품질 집계

| 항목 | 결과 |
| --- | ---: |
| 전체 | 16 |
| 평가 완료 | 16 |
| 관련성 | 14/16 |
| 사실성 | 16/16 |
| 인용 구조 | 15/16 |
| 근거 없음 | 0 |
| 검색 실패 | 0 |
| 답변 생성 실패 | 0 |

## 문항별 판정

| 문항 ID | 상태 | 관련성 | 사실성 | 인용 구조 |
| --- | --- | --- | --- | --- |
| etl-01 | judged | 통과 | 통과 | 통과 |
| etl-02 | judged | 통과 | 통과 | 통과 |
| etl-03 | judged | 실패 | 통과 | 실패 |
| etl-ko | judged | 통과 | 통과 | 통과 |
| pg-01 | judged | 통과 | 통과 | 통과 |
| pg-02 | judged | 통과 | 통과 | 통과 |
| pg-03 | judged | 통과 | 통과 | 통과 |
| pg-ko | judged | 통과 | 통과 | 통과 |
| memory-01 | judged | 통과 | 통과 | 통과 |
| memory-02 | judged | 통과 | 통과 | 통과 |
| memory-03 | judged | 통과 | 통과 | 통과 |
| memory-ko | judged | 통과 | 통과 | 통과 |
| client-01 | judged | 통과 | 통과 | 통과 |
| client-02 | judged | 통과 | 통과 | 통과 |
| client-03 | judged | 실패 | 통과 | 통과 |
| client-ko | judged | 통과 | 통과 | 통과 |
