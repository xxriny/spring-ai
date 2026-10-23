# 로컬 검색 실측: 2026-10-23

## 조건

- 공식 Spring AI 문서 URL 4개를 한 쪽씩 수집해 174개 청크를 색인하고, `evaluation/questions.json`의 고정 질문 16개(영어 12개, 한국어 4개)를 검색했다.
- Ollama `qwen3-embedding:8b/1536`, pgvector Top-10/유사도 기준 0.3, pg_search BM25 Top-10, RRF(k=60) Top-10, `Qwen/Qwen3-Reranker-0.6B`, 최종 passage Top-3을 사용했다. 검색 오류는 0개였다.
- 원시 결과는 로컬의 Git 제외 경로 `evaluation/results/20261023T075116Z/`에 있다. 재현 시 `python3 evaluation/evaluate.py --execute`를 실행하면 현재 시각의 새 결과 디렉터리가 생성된다.
- 아래 수치는 HTML 중복 제거 변경 전의 기준선이다. 변경 후 품질은 공식 문서를 재색인하고 같은 질문으로 다시 측정하기 전까지 미측정이다.

## 결과

| 단계 | 문서 Hit@1 | 근거 문구 Hit@1 | 근거 문구 Hit@3 | 근거 문구 MRR |
| --- | ---: | ---: | ---: | ---: |
| 벡터 | 100.0% | 68.8% | 87.5% | 0.802 |
| BM25 | 75.0% | 43.8% | 68.8% | 0.571 |
| RRF 결합 | 100.0% | 75.0% | 87.5% | 0.837 |
| Qwen 재정렬 | 87.5% | 50.0% | 87.5% | 0.703 |
| 최종 passage | 87.5% | 50.0% | 87.5% | 0.677 |

한국어 4문항의 근거 문구 Hit@1은 벡터 50%, BM25 25%, RRF 75%, 재정렬과 passage 각각 25%였다. 표본이 작으므로 언어별 일반 성능으로 해석하지 않는다.

## 누락 사례와 해석

- `etl-03` (PDF 페이지 리더): 정답인 `PagePdfDocumentReader` 설명은 벡터 1위였지만 RRF 7위, 재정렬 6위가 되어 최종 Top-3에서 빠졌다. BM25 상위에 유사한 `Dependencies` 청크가 여러 개 들어왔고, 재정렬도 이 청크들에 더 높은 점수를 부여했다. 현재 고정 Top-3에서는 실제 근거 누락이다.
- `pg-02` (PGvector 스키마 초기화): `initialize-schema=true`를 포함한 `Auto-Configuration` 청크는 재정렬 4위였다. 최종 3위 passage에는 `spring.ai.vectorstore.pgvector.initialize-schema` 속성과 기본값 `false` 설명이 있으나, 평가의 정확한 문자열 `initialize-schema=true`는 없다. 평가 기준상 누락이며, passage가 질문에 충분한지는 별도로 사람이 판정해야 한다.
- `etl-03`의 동일한 PDF 의존성 내용이 서로 다른 청크 ID로 중복 색인된 사례도 확인했다. 원본 HTML에는 `PDF Page`와 `PDF Paragraph` 아래에 같은 `Dependencies` 절이 각각 있으며, 추출기는 `<li><p>Maven</p></li>`의 부모·자식 텍스트도 두 번 읽었다. 두 현상을 별도 실패 테스트로 재현한 뒤, 중첩 자식의 재추출과 제목·본문이 완전히 같은 절의 중복 색인을 막았다. 기존 색인의 청크는 그대로 남아 있다.

현재 Python 리랭커의 `CrossEncoder(...).predict((query, document) ...)` 호출은 [Qwen 모델 카드의 Sentence Transformers 예시](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B)와 같다. 이번 결과만으로 모델 호출 오류나 전반적인 모델 성능을 단정하지 않는다. 다음 검증은 문서를 재색인하고 동일한 질문·근거 기준으로 변경 전후를 재측정하는 것이다. 이 수치는 검색 근거의 문자열 일치율이며 생성 답변의 정확도가 아니다.
