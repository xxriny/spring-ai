# 하이브리드 검색 명세 (v0.1.0)

## API와 권한

`GET /api/search?query=<질문>&scope=SHARED|PRIVATE`는 HTTP Basic 인증이 필요하다. `scope` 기본값은 `SHARED`이며 `PRIVATE`의 소유자는 요청자가 지정할 수 없고 인증 주체로 결정한다. 질문은 공백만 있는 문자열을 허용하지 않으며 길이는 1~4,000자다. 잘못된 질문·범위는 `400 {"error":"..."}`, 인증 실패는 `401`, 리랭커 장애는 `503 {"error":"리랭커를 사용할 수 없습니다."}`다. 응답 헤더 `X-Trace-Id`에는 요청 UUID를 돌려준다.

응답의 다섯 배열은 모두 순위가 높은 순이다.

| 필드 | 의미 | 최대 건수 |
| --- | --- | ---: |
| `vectorCandidates` | 코사인 유사도 0.3 이상인 pgvector 결과 | 10 |
| `bm25Candidates` | `pg_search` BM25 결과 | 10 |
| `candidates` | 청크 ID로 합치고 RRF(k=60) 점수 순으로 정렬한 후보 | 10 |
| `reranked` | 같은 후보를 Qwen 점수 순으로 정렬 | 10 |
| `passages` | 재정렬 상위 3개 앵커와 같은 문서·페이지의 인접 청크를 추출식으로 압축한 근거 | 3 |

각 항목은 `chunkId`, `documentId`, `title`, `url`, `section`, `category`, `text`, `page`, `rank`, `vectorScore`, `rerankerScore`, `sourceChunkIds`를 가진다. `vectorScore`는 벡터 검색에서 온 경우에만 있고, `rerankerScore`는 `reranked`와 `passages`에만 있다. BM25 점수와 RRF 점수는 현재 응답에 노출하지 않는다. `sourceChunkIds`는 passage 구성에 사용한 청크 ID 목록이다. 검색 결과가 없으면 모든 배열이 비고 리랭커를 호출하지 않는다.

`POST /api/conversations/{id}/messages`의 요청은 `{"text":"질문"}`이며, 생성 모델 연결 후 해당 대화의 범위와 소유권으로 검색한 **최종 `passages`**를 답변 근거로 사용한다. 현재 생성 모델이 없으므로 이 요청은 `503`이다. JDBC chat memory는 이전 대화 저장을 담당한다. MultiQueryExpander와 모델 기반 압축은 생성 모델 선택 후 연결한다. 현재 검색 API는 질문 원문 하나만 사용한다.

## 검색 순서

1. 인증 범위로 pgvector Top-10(유사도 기준 0.3)과 BM25 Top-10을 각각 검색한다. `0.3`은 BM25·RRF·Qwen 점수에 적용하지 않는다.
2. 청크 ID가 같은 결과는 하나로 합친다. 각 목록의 1부터 시작하는 순위에 `1/(60+rank)`를 부여하고 합한 RRF 순위 상위 10개만 Qwen에 전달한다.
3. Qwen이 준 점수로 정렬하고 상위 3개 앵커의 앞뒤 `chunkOrder` 청크를 조회한다. 이웃 청크는 같은 `documentId`, `contentType`, PDF 페이지, HTML 섹션이어야 하며 표 행은 `tableIndex`도 같아야 한다. SQL에서 같은 범위와 소유자만 조회한다.
4. 이웃 청크는 각 최대 200토큰으로 자른다. 본문 청크는 오버랩을 제거하고, Markdown 표 행은 행 경계를 유지하도록 줄바꿈으로 잇는다. 최종 본문은 앵커 앞뒤 순서로 결합하고 passage당 1,200토큰으로 제한한다. 3개 passage의 총 상한은 3,600토큰이다. 이는 결정적 추출식 절단이며 생성 모델의 요약은 아니다.

구조화 로그에는 `traceId`, 범위, 각 단계의 건수와 소요 시간만 기록한다. 질문, 문서 본문, 인증값은 기록하지 않는다.

## 데이터 모델·색인

`knowledge_document(id, scope, owner_id, title, category, source_url, stored_path, sha256, status, created_at, updated_at)`가 문서 카탈로그다. `knowledge_chunk(document_id, chunk_id)`는 문서와 검색 청크의 관계이며 문서 삭제 시 같이 삭제된다. Spring AI가 관리하는 `vector_store(id, content, metadata, embedding)`는 실제 검색 본문·JSON 메타데이터·Ollama `qwen3-embedding:8b`의 1536차원 임베딩의 단일 저장소다. 모델 출력을 1536차원으로 요청해 기존 pgvector HNSW `vector` 인덱스를 유지한다. 이 테이블에 pgvector HNSW 인덱스와 `vector_store_bm25_idx` (`USING paradedb (id, content)`)가 있다. 앱 시작 시 `pg_search` 확장과 BM25 인덱스를 생성하므로 기존 행도 색인된다. 문서 등록·갱신·삭제는 기존 VectorStore 저장 경로를 따른다. 모델이 다른 기존 1536차원 벡터는 서로 검색할 수 없으므로 `embeddingModel`이 현재 값과 다른 행이 있으면 시작을 중단한다.

메타데이터에는 `documentId`, `scope`, `ownerId`, `title`, `source`, `category`, `chunkOrder`, `embeddingModel=qwen3-embedding:8b/1536`이 들어간다. `PRIVATE`의 `ownerId`는 인증 계정이며 `SHARED`는 공용이다. PDF에는 `page_number`, `contentType=BODY|TABLE_ROW`, 표 행에는 `tableIndex`, `rowIndex`, `tableExtractionStatus`가 추가된다. 벡터 필터와 BM25·이웃 청크 SQL에서 모두 `scope`와 `ownerId`를 제한한다. DB 전환과 복구 절차는 [로컬 런북](../runbooks/SpringRagLocalV0_1_0.md)을 따른다.

HTML 수집에서는 선택한 블록 요소의 중첩 자식 텍스트를 다시 읽지 않고, 같은 HTML 문서 안에서 `section` 제목과 본문이 모두 동일한 절은 한 번만 색인한다. 내용이 다른 동명 절은 유지한다. 기존 색인에는 자동 적용되지 않는다.

## 평가

`evaluation/questions.json`의 공식 Spring AI 문서 4쪽·질문 16개에 대해 `vectorCandidates`, `bm25Candidates`, `candidates`, `reranked`, `passages` 각각 문서·근거 문구 Hit@1/3/5/10과 MRR을 동일한 기대 URL·문구 기준으로 산출한다. `python3 evaluation/evaluate.py`는 입력 검사만 수행하고 품질은 **미측정**으로 표시한다. 실제 수집·검색 `--execute`는 로컬 Ollama 임베딩을 사용한다. 결과는 `evaluation/results/<UTC>/`에 저장하며 오류 사례는 성공 지표에서 제외한다.

최초 로컬 실측과 누락 사례는 [2026-10-23 평가 기록](LocalRetrievalEvaluation2026-10-23.md)에, HTML 중복 제거 후 재색인한 결과는 [2026-10-26 To-Be 평가](LocalRetrievalEvaluation2026-10-26-ToBe.md)에 정리했다.

이후 검색 품질 변경 기준은 [ADR 0001](adr/0001-measure-retrieval-before-tuning.md)을 따른다.
