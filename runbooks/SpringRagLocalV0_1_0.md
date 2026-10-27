# Spring RAG 챗봇 로컬 운영 런북 (v0.1.0)

이 문서는 현재 `admin`·`user` 두 계정을 사용하는 로컬 첫 버전의 기동, 확인, 장애 대응 절차다. 명령은 저장소 루트에서 실행한다. Ollama `qwen3.5:9b`가 답변을 생성하고 Qwen 리랭커가 검색 후보를 재정렬한다.

## 구성과 데이터

| 구성 요소 | 기본 위치 | 역할 |
| --- | --- | --- |
| PostgreSQL 17/pgvector/pg_search | ParadeDB `0.26.0-pg17` Compose `db`, 호스트 `localhost:5432` | 문서 목록, 벡터·BM25 인덱스, 대화·채팅 메모리 |
| Ollama `qwen3-embedding:8b`·`qwen3.5:9b` | `127.0.0.1:11434` | 문서·검색 질문 임베딩, 후속 질문 재작성과 답변 생성 |
| Spring Boot | `localhost:8080` | 인증, ETL, 검색·리랭킹·대화 API 및 NDJSON 답변 스트림 |
| Qwen 리랭커 | `127.0.0.1:8091` | 검색 후보 1~10개에 대한 로컬 재정렬 |
| PDF 추출·PaddleOCR | `127.0.0.1:8092` | pdfplumber 본문·표 추출, 텍스트 없는 페이지의 한국어 OCR |
| React/Vite | `localhost:5173` | 로그인, 문서 등록, 대화 화면 |
| 파일 원본 | `UPLOAD_DIR`, 기본 `./data/uploads` | 업로드한 원본 파일 |
| 평가 원시 결과 | `evaluation/results/<UTC>/` | 수집·검색 응답과 지표; Git에서 제외됨 |

문서 적재와 검색 질문 임베딩은 로컬 Ollama `qwen3-embedding:8b`를, 대화 재작성·답변은 `qwen3.5:9b`를 사용한다. 첫 실행에 두 모델을 다운로드하며 OpenAI API 키는 필요하지 않다.

## 기동 전 확인

1. Java 17+, Maven, Node.js 20+, Python, Docker를 준비한다. Docker가 실행 중인지 `docker info`로 확인한다.
2. `5432`, `8080`, `8091`, `8092`, `11434`, `5173` 포트의 기존 사용 여부를 확인한다. 이미 실행 중인 이 프로젝트 프로세스가 있으면 중복 실행하지 않는다.
3. 관리자·사용자 비밀번호를 준비한다. 실제 값을 저장소에 커밋하거나 명령줄 인수에 넣지 않는다.
4. 기존 DB를 재사용한다면 `infrastructure/compose.yaml`의 `pgdata` 볼륨과 `UPLOAD_DIR`이 기대한 데이터인지 확인한다. `docker compose -f infrastructure/compose.yaml down -v`는 볼륨을 삭제하므로 사용하지 않는다.

Compose 파일을 `infrastructure/`로 옮겨도 프로젝트명은 `springai`로 고정되어 기존 `springai_pgdata` 볼륨을 참조한다. 모든 Compose 명령은 저장소 루트에서 실행한다. Docker 엔진이 없는 환경에서는 볼륨의 실제 연결 상태까지 확인할 수 없으므로, 기동 전에 `docker compose -f infrastructure/compose.yaml config`와 기존 볼륨을 확인한다.

### 기존 pgvector 이미지에서 ParadeDB로 전환

앱의 업로드·수집·삭제를 멈추고, **기존 이미지의 DB가 실행 중일 때** 다음 백업을 만든다. 덤프와 `UPLOAD_DIR`은 같은 시점의 보관 세트로 취급하고 파일 권한을 제한한다. 아래 `/secure/backup`은 저장소 밖의 실제 보관 경로로 바꾼다.

```bash
umask 077
BACKUP_DIR=/secure/backup
mkdir -p "$BACKUP_DIR"
docker compose -f infrastructure/compose.yaml exec -T db pg_dump -U springrag -d springrag -Fc > "$BACKUP_DIR/springrag-before-paradedb.dump"
tar -C data -czf "$BACKUP_DIR/uploads-before-paradedb.tgz" uploads
docker compose -f infrastructure/compose.yaml exec -T db pg_restore --list < "$BACKUP_DIR/springrag-before-paradedb.dump" | head
```

별도 ParadeDB `0.26.0-pg17` 인스턴스에 덤프를 복원하고 `knowledge_document`, `knowledge_chunk`, `vector_store` 건수와 샘플 데이터를 원본과 비교한다. 기존 DB를 복원 대상으로 쓰지 않는다. 검증이 끝난 뒤 DB를 중지하고 `infrastructure/compose.yaml`의 이미지를 `paradedb/paradedb:0.26.0-pg17`로 바꿔 **같은 `pgdata` 볼륨**으로 재기동한다. PostgreSQL 메이저 버전은 17로 유지한다. 볼륨을 지우거나 새 DB로 초기화하지 않는다. 이미지 교체 후 collation 버전 경고가 있으면 다음 명령을 백업 상태에서 수행한다.

```bash
docker compose -f infrastructure/compose.yaml up -d db
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'REINDEX DATABASE springrag;'
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'ALTER DATABASE springrag REFRESH COLLATION VERSION;'
```

`infrastructure/compose.yaml`은 기존 볼륨의 설정과 관계없이 `shared_preload_libraries=pg_search`로 PostgreSQL을 시작한다. 이 설정이 빠지면 `CREATE EXTENSION pg_search`가 실패한다. 백엔드가 시작되면 `pg_search` 확장과 `vector_store_bm25_idx`가 기존 `vector_store` 행까지 색인한다. 아래 조회로 preload·확장·인덱스·건수를 확인한다. 실패하면 앱과 DB를 중지하고 백업과 원본 업로드 파일을 검증된 복원 절차로 되돌린다. 덤프만 복원하거나 `stored_path`가 가리키는 파일을 누락하면 안 된다.

```bash
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'SELECT version();'
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'SHOW shared_preload_libraries;'
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c "SELECT extname FROM pg_extension WHERE extname IN ('vector','pg_search');"
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c "SELECT indexname FROM pg_indexes WHERE tablename='vector_store' AND indexname='vector_store_bm25_idx';"
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'SELECT count(*) FROM vector_store;'
```

| 환경 변수 | 기본값 | 설정 목적 |
| --- | --- | --- |
| `APP_ADMIN_PASSWORD` | 없음, 필수 | 로컬 `admin` 로그인 |
| `APP_USER_PASSWORD` | 없음, 필수 | 로컬 `user` 로그인 |
| `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | 로컬 임베딩 서버 주소 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/springrag` | DB 접속 주소 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `springrag` / `springrag` | DB 계정 |
| `UPLOAD_DIR` | `./data/uploads` | 업로드 원본 위치 |
| `RERANKER_URL` | `http://127.0.0.1:8091/rerank` | Spring Boot에서 호출할 로컬 리랭커 |
| `OCR_URL` | `http://127.0.0.1:8092/ocr` | 스캔 PDF 페이지에 사용할 로컬 OCR |
| `PDF_EXTRACTION_URL` | `http://127.0.0.1:8092/pdf/extract` | 모든 PDF 등록 시 호출하는 로컬 추출 서버 |

## 기존 OpenAI 벡터 재색인

모델이 달라도 1536차원 수치는 같을 수 있지만 벡터 의미 공간은 다르다. 앱은 `vector_store.metadata.embeddingModel`이 `qwen3-embedding:8b/1536`이 아닌 행이 있으면 시작을 중단한다. 배포 전 아래 읽기 전용 조회로 건수를 확인한다.

```bash
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c "SELECT count(*) FROM vector_store WHERE metadata->>'embeddingModel' IS DISTINCT FROM 'qwen3-embedding:8b/1536';"
```

0건이면 데이터 변경 없이 시작한다. 1건 이상이면 DB 덤프와 `UPLOAD_DIR` 파일을 함께 백업하고 별도 DB에 복원해 검증한다. 아래 조회로 파일·URL, 소유자·범위·카테고리를 보관하고 원본 파일의 존재를 확인한다.

```bash
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'SELECT id,scope,owner_id,title,category,source_url,stored_path FROM knowledge_document ORDER BY created_at;'
```

백업·복원 및 재등록 목록을 확인한 뒤 **앱을 중지한 상태에서만** 다음 트랜잭션을 실행한다. 기존 청크와 카탈로그를 모두 지우며 대화 기록은 보존한다. SQL 오류가 나면 `ROLLBACK`하고 백업을 점검한다.

```sql
BEGIN;
DELETE FROM vector_store;
DELETE FROM knowledge_document;
COMMIT;
```

그 뒤 Ollama와 앱을 시작하고 공용 파일·URL을 관리자 계정으로, 개인 파일을 해당 사용자 계정으로 다시 등록한다. `vector_store`와 `knowledge_document` 건수, `embeddingModel` 값, 샘플 검색 결과를 확인한다. 원본 파일이 없으면 재색인할 수 있으리라 가정하지 않는다. 임의로 메타데이터만 바꾸는 작업은 금지한다. 이 버전은 기존 대화 기록의 문서 참조를 자동 갱신하지 않는다.

## 시작 순서

### 1. 데이터베이스

```bash
docker compose -f infrastructure/compose.yaml up -d db
docker compose -f infrastructure/compose.yaml ps db
docker compose -f infrastructure/compose.yaml exec -T db pg_isready -U springrag -d springrag
```

`pg_isready`가 연결 가능 상태를 반환한 뒤 다음 단계로 간다. 데이터는 Compose의 `pgdata` 볼륨에 남는다. 기존 DB 전환은 위 백업·복원 확인 후 수행한다.

### 2. 로컬 임베딩·생성

Ollama를 설치하고 서비스를 실행한 뒤 모델을 받는다. 기본 주소는 루프백 `127.0.0.1:11434`다. 임베딩 모델은 약 4.7GB, 생성 모델은 약 6.6GB이며 추론에는 별도 메모리가 필요하다. 서버가 다른 주소라면 `OLLAMA_BASE_URL`, 생성 모델을 바꾸려면 `OLLAMA_CHAT_MODEL`을 설정한다.

```bash
ollama pull qwen3-embedding:8b
ollama pull qwen3.5:9b
ollama list
```

Ollama가 자동으로 실행되지 않는 환경에서는 별도 터미널에서 `ollama serve`를 실행한다. 모델 목록만으로 추론 성공을 보장하지 않으므로 문서 한 건의 등록·검색과 대화 질문으로 확인한다. 기존 OpenAI 임베딩 청크가 있는 DB는 위 재색인 절차를 먼저 따른다.

### 3. 로컬 리랭커

첫 실행 전에 [모델 카드](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B)의 다운로드 용량과 사용 가능한 메모리·CPU를 확인한다. macOS Metal 추론이 Ollama 생성 모델과 동시에 동작할 때 종료된 사례가 있어 현재 서버는 CPU·최대 256토큰 입력으로 추론한다. 백엔드의 리랭커 요청 제한 시간은 120초다. 설치와 서버 시작은 별도 터미널에서 실행한다.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r infrastructure/reranker/requirements.txt
python infrastructure/reranker/server.py
```

모델 로드가 끝나면 서버가 `127.0.0.1:8091`에 바인딩된다. `curl -fsS -X POST http://127.0.0.1:8091/rerank -H 'Content-Type: application/json' -d '{"query":"Spring AI","documents":["Spring AI 문서"]}'`로 실제 점수 응답을 확인한다. 모델 파일은 저장소에 넣지 않는다.

2026-10-26 로컬 확인에서는 CPU·256토큰 제한으로 후보 10개 재정렬이 약 24초에 끝났고, `qwen3.5:9b` 답변 API가 HTTP 200·출처 3건을 반환했다. 이 한 번의 정상 응답은 답변 정확도나 전체 16문항 품질을 뜻하지 않는다. CPU 사용량이 높고 입력 뒷부분이 잘리므로 지연과 검색 품질을 별도로 평가해야 한다.

### 4. 로컬 PDF 추출·OCR

모든 PDF를 등록할 때 필요하다. pdfplumber가 텍스트 PDF의 반복 머리말·꼬리말과 표 행을 처리하고, 스캔 페이지만 OCR을 호출한다. [PaddleOCR 설치 안내](https://www.paddleocr.ai/main/en/version3.x/installation.html)에 맞는 Python·PaddlePaddle 환경을 준비하고, 별도 터미널에서 실행한다. 첫 시작에는 한국어 PP-OCRv5 모델을 다운로드하므로 용량과 저장 공간을 먼저 확인한다.

```bash
python3 -m venv .venv-ocr
source .venv-ocr/bin/activate
python -m pip install -r infrastructure/ocr/requirements.txt
python infrastructure/ocr/server.py
```

서버는 `127.0.0.1:8092`에만 바인딩한다. `nc -z 127.0.0.1 8092`는 포트 접속만 확인하며 PDF 추출·한글 인식 품질을 보증하지 않는다. PDF 추출 요청은 `application/pdf` 원본 바이트로 `/pdf/extract`에 전달되며 20MB를 초과하면 거부된다. 추출 실패 시 업로드는 오류로 끝나고, URL 재수집은 기존 색인을 유지한다. 표 구조를 감지하지 못하면 페이지 본문으로 색인한다. 긴 표 열 제목은 페이지·표·열마다 한 번만 제목 청크로 저장하고 셀 청크에는 열 번호를 남긴다. 새 색인의 `columnIndex`·`tablePart`를 사용해 뒤쪽 행을 검색할 때도 같은 열의 제목 조각을 passage에 붙인다. 기존 PDF에 변경을 적용하려면 해당 문서를 삭제한 뒤 재등록한다. 이 작업은 로컬 Ollama 추론을 사용한다.

### 5. Spring Boot와 화면

백엔드 터미널에서 환경 변수를 설정하고 시작한다. 예를 들어 Git에서 제외된 `.env`에 `APP_ADMIN_PASSWORD`, `APP_USER_PASSWORD`를 각 한 줄씩 `이름=값` 형식으로 저장한다. 파일 권한을 제한하고, 실제 값을 터미널 명령에 직접 입력하지 않는다.

```bash
chmod 600 .env
set -a
source .env
set +a
mvn spring-boot:run
```

화면은 별도 터미널에서 시작한다.

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

브라우저에서 `http://localhost:5173`을 열고 `admin` 또는 `user`로 로그인한다. 백엔드 시작 시 `schema.sql`, Spring AI의 pgvector·JDBC 메모리 스키마 초기화가 실행된다. 시작 실패 시 아래 장애 대응을 확인한다.

## 상태 확인

다음 확인은 문서 임베딩이나 검색 질문 임베딩을 호출하지 않는다.

```bash
curl -fsS http://127.0.0.1:8080/api/health
curl -u admin http://127.0.0.1:8080/api/me
curl -u user 'http://127.0.0.1:8080/api/documents?scope=PRIVATE'
curl -u admin http://127.0.0.1:8080/v3/api-docs
docker compose -f infrastructure/compose.yaml exec -T db psql -U springrag -d springrag -c 'SELECT count(*) FROM knowledge_document;'
```

`curl -u admin`과 `curl -u user`는 비밀번호를 명령 인수에 쓰지 않으면 입력을 요청한다. `/api/health`의 `{"status":"UP"}`는 **HTTP 핸들러만** 확인한다. DB, Ollama, Qwen 리랭커 준비 상태를 보증하지 않는다. DB는 `pg_isready`와 읽기 전용 조회로, Qwen은 프로세스 로그와 포트로 따로 확인한다.

브라우저 API 문서는 `http://127.0.0.1:8080/swagger-ui.html`에서 연다. `/v3/api-docs`와 Swagger UI 모두 HTTP Basic 인증이 필요하다. JSON 응답의 `paths`에서 `/api/search` 등을, `components.securitySchemes.basicAuth`에서 인증 방식을 확인한다. 문서가 열려도 검색 의존 서비스의 준비 상태가 보장되는 것은 아니다.

### 채팅 스트림 확인

공용 또는 개인 문서 범위의 대화를 만든 뒤 반환된 `id`를 사용한다. 첫 명령의 `id`를 두 번째 줄의 `read` 프롬프트에 붙여넣고 Enter를 누른다. 아래 명령은 비밀번호를 인수에 넣지 않고 프롬프트에서 입력받는다. `-N`은 curl 출력 버퍼링을 끈다.

```bash
curl -u admin -H 'Content-Type: application/json' -d '{"scope":"SHARED"}' http://127.0.0.1:8080/api/conversations
read -r CONVERSATION_ID
curl -N -u admin -H 'Content-Type: application/json' -H 'Accept: application/x-ndjson' \
  -d '{"text":"Spring AI ETL 단계는 무엇인가요?"}' \
  "http://127.0.0.1:8080/api/conversations/$CONVERSATION_ID/messages/stream"
```

응답은 한 줄에 한 이벤트이며 `sources` → 여러 `delta` → `done` 순서다. `done`이 없으면 요청이 끝나지 않았거나 중간에 실패한 것이므로 화면용 답변 저장 여부를 메시지 조회 API로 확인한다. 첫 조각 전에 동기식 검색·리랭킹이 끝나야 한다. 2026-10-26 로컬 한 건에서는 출처 3건, `delta` 100건, 첫 조각 약 31초, 전체 약 37초였다. 이 값은 성능 기준선이 아니다.

## 문서 등록과 검색

아래 요청은 로컬 Ollama 임베딩을 실행한다. 대상 파일·페이지 수와 예상 청크 수를 확인하고 CPU/GPU·메모리 사용량을 관찰한다. URL의 `maxPages=1`은 한 페이지만 수집하지만 페이지당 청크 수·임베딩 호출 수를 고정하지는 않는다.
새 색인은 청크당 최대 800토큰, 같은 PDF 페이지 또는 HTML 섹션 내 인접 청크 간 최대 100토큰 오버랩을 사용한다. 기존 색인은 자동 변경되지 않으므로 적용 대상 문서를 삭제한 뒤 다시 등록한다. 동일 내용의 URL을 단순 재수집하면 건너뛰므로 먼저 삭제해야 한다.

```bash
# 관리자: 공용 파일 등록
curl -u admin -F 'file=@/path/to/document.pdf' -F 'category=Spring AI docs' \
  http://127.0.0.1:8080/api/documents

# 사용자: 내 문서 등록
curl -u user -F 'file=@/path/to/notes.txt' -F 'category=Personal' \
  http://127.0.0.1:8080/api/documents

# 관리자: 지정 URL 한 쪽만 수집
curl -u admin -H 'Content-Type: application/json' \
  -d '{"url":"https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html","category":"Spring AI docs","maxPages":1}' \
  http://127.0.0.1:8080/api/admin/crawl

# 공용 문서 검색. PRIVATE를 지정하면 로그인한 계정의 내 문서만 검색한다.
curl -G -u user --data-urlencode 'query=How does the ETL pipeline work?' \
  --data-urlencode 'scope=SHARED' http://127.0.0.1:8080/api/search
```

수집 응답의 `indexed`, `skipped`, `errors`를 함께 확인한다. `indexed=1`만으로 기대 문서의 근거 청크가 검색된다고 판단하지 않는다. `vectorCandidates`는 벡터 Top-10·유사도 기준 0.3, `bm25Candidates`는 BM25 Top-10, `candidates`는 RRF(k=60) 결합 Top-10, `reranked`는 Qwen 순위, `passages`는 앞뒤 청크를 결합·오버랩 제거한 최종 Top-3이다. `0.3`은 벡터에만 적용한다. `PRIVATE` 범위의 소유자는 요청 파라미터가 아닌 로그인 계정에서 결정된다. 응답의 `X-Trace-Id`를 기록하면 구조화 로그에서 단계별 건수·소요 시간을 연결할 수 있다. 질문·원문·비밀값은 로그에 남기지 않는다.

## 검색 평가

```bash
python3 evaluation/evaluate.py
python3 -m unittest discover -s evaluation -p 'test_*.py'
```

기본 명령은 공식 문서 URL 4개와 질문 16개를 검증하고 품질을 **미측정**으로 표시한다. 실제 평가는 Ollama·Qwen 리랭커와 백엔드를 기동한 뒤 `EVAL_ADMIN_PASSWORD`를 설정하고 `python3 evaluation/evaluate.py --execute`로 실행한다. 이 명령은 URL별 `maxPages=1`로 수집 4회와 검색 질문 16개를 수행한다. 원시 응답과 결과는 `evaluation/results/<UTC>/`의 `config.json`, `ingest.jsonl`, `search.jsonl`, `summary.json`에서 확인한다. 오류가 난 질문은 성공한 지표 분모에 섞이지 않는다. 문서·청크 Hit@1/3/5/10과 MRR은 벡터, BM25, 결합, 재정렬, 최종 passage 각각 계산되며 답변 정확도를 뜻하지 않는다. 로컬 모델 추론이므로 외부 임베딩 API 과금은 없다. 실행 중 메모리·처리 시간을 관찰한다.

HTML 중첩 텍스트·동일 절 중복 제거는 기존 색인에 자동 반영되지 않는다. 같은 URL의 내용 해시와 카테고리가 같으면 수집 시 재색인을 건너뛰므로, 변경 전후 품질을 비교하려면 DB와 업로드 원본을 백업한 뒤 대상 공식 문서를 삭제하고 다시 수집한다. 재수집 성공과 청크 건수·검색 결과를 확인하기 전에는 전체 데이터나 볼륨을 삭제하지 않는다.

2026-10-23 기준 실측과 누락 질문은 [검색 평가 기록](../docs/LocalRetrievalEvaluation2026-10-23.md)에, 2026-10-26 재색인 후 비교 결과는 [To-Be 평가](../docs/LocalRetrievalEvaluation2026-10-26-ToBe.md)에 남겼다. 현재 CPU·256토큰 리랭커는 이 평가 뒤 변경됐으므로 16문항 지표가 미측정이다. 재평가 시 같은 질문·근거 기준의 결과를 비교한다.

## 종료와 백업

화면·백엔드·리랭커·OCR은 각 실행 터미널에서 정상 종료한다. DB만 중지할 때는 `docker compose -f infrastructure/compose.yaml stop db`를 사용한다. `docker compose -f infrastructure/compose.yaml down`은 컨테이너를 내리지만 명명 볼륨은 유지한다. **`down -v`는 사용하지 않는다.**

백업 중 문서 등록·삭제·재수집을 중단한다. PostgreSQL 덤프와 `UPLOAD_DIR` 원본을 같은 백업 세트로 보관하고, 평가 원시 결과가 필요하면 `evaluation/results/`도 따로 보관한다. 기본 업로드 경로를 사용하는 경우의 예시는 다음과 같다.

```bash
BACKUP_DIR=/path/to/backups
mkdir -p "$BACKUP_DIR"
docker compose -f infrastructure/compose.yaml exec -T db pg_dump -U springrag -d springrag -Fc > "$BACKUP_DIR/springrag.dump"
tar -C data -czf "$BACKUP_DIR/uploads.tgz" uploads
docker compose -f infrastructure/compose.yaml exec -T db pg_restore --list < "$BACKUP_DIR/springrag.dump" | head
```

복구는 우선 별도 DB/작업 디렉터리에서 덤프를 복원해 문서·청크·벡터 건수와 샘플 검색을 확인한다. 검증 뒤 앱을 중지하고 운영 DB 및 원본 파일의 **같은 경로**를 복원한다. DB의 `stored_path`는 업로드 원본 경로를 참조하므로 `UPLOAD_DIR`만 다른 곳으로 옮기지 않는다. 기존 DB나 볼륨을 바로 덮어쓰는 복원은 이 런북에서 자동 실행하지 않는다.

## 장애 대응

| 증상 | 확인 | 조치 |
| --- | --- | --- |
| 백엔드 시작 시 DB 연결 실패 | `docker compose -f infrastructure/compose.yaml ps db`, `pg_isready`, `DATABASE_*` | DB를 시작하고 접속 정보를 맞춘 뒤 백엔드 재시작 |
| 백엔드 시작 시 기존 임베딩 문서 오류 | `vector_store.metadata.embeddingModel`, 기존 청크 건수 | 위 백업·복원·재색인 절차를 완료한 뒤 시작. 메타데이터만 수정하지 않음 |
| 로그인 401 | `/api/me`, 계정명·비밀번호 환경 변수 | `admin`/`user` 계정과 비밀번호 재확인. 비밀번호 변경 시 백엔드 재시작 |
| 관리자 수집 403 | 로그인 계정 | `/api/admin/crawl`은 `admin`만 호출 가능 |
| 등록·검색 400 | 카테고리 1~80자, 지원 파일 형식·20MB 제한, 질문 1~4000자, 범위 | 요청 값을 수정하고 재시도. 실패 URL은 `errors` 확인 |
| 검색 503, 리랭커 오류 | Qwen 프로세스 로그, `8091` 포트, `RERANKER_URL` | 리랭커를 기동/복구한 뒤 같은 질문 재시도. 후보가 없으면 리랭커를 호출하지 않음 |
| 채팅 스트림에 `done` 없음 | Ollama·리랭커 상태, `X-Trace-Id`, 대화 메시지 조회 | 연결 끊김 또는 생성 오류를 확인한다. 완료 전 답변은 화면용 기록에 저장하지 않음 |
| 색인·검색 중 Ollama 연결/메모리 오류 | `ollama list`, Ollama 로그, `OLLAMA_BASE_URL`, 시스템 메모리 | 모델과 서버를 확인하고 메모리를 확보한 뒤 재시도 |
| 검색에서 BM25 인덱스 오류 | `pg_search` 확장·`vector_store_bm25_idx`, DB 이미지 버전, `X-Trace-Id` | ParadeDB 0.26.0-pg17과 인덱스를 확인하고 앱 시작 로그를 조사. DB 전환 직후라면 백업·복원 확인부터 재검토 |
| PDF 등록 `503` 또는 URL 수집 `errors`에 추출·OCR 오류 | PDF 추출·PaddleOCR 프로세스 로그, `8092` 포트, `PDF_EXTRACTION_URL`, `OCR_URL` | `.venv-ocr/bin/python infrastructure/ocr/server.py`로 서버를 기동하고 재시도. 실패한 URL 재수집은 기존 색인을 유지함 |
| 대화 질문 실패, Ollama 생성 모델 오류 | `ollama list`, Ollama 로그, `OLLAMA_CHAT_MODEL`, `OLLAMA_BASE_URL` | 생성 모델을 받은 뒤 Ollama 서버·메모리를 확인하고 재시도. 독립 `/api/search`로 검색 경로 분리 확인 |
| 색인·검색 중 Ollama/DB 오류 | 백엔드 로그, `ollama list`, `OLLAMA_BASE_URL`, DB 상태 | 원인 복구 후 재시도. 변경 URL 재수집 실패 시 기존 카탈로그·청크가 유지되도록 구현됨. 조회로 확인 |
| 수집 `indexed=0` 또는 `errors` 발생 | URL별 HTTP 상태, 경로 범위, 리다이렉트, 파일 형식 | 오류 URL만 재시도. 동일 URL·동일 내용은 중복 색인하지 않음 |

URL 수집은 동기식이며 관리자만 실행할 수 있다. DNS 검사 뒤 접속할 때 주소가 바뀌는 위험이 남아 있으므로, 신뢰하지 않는 관리자에게 URL 수집 권한을 주는 환경에서는 서버 네트워크의 외부 송신 제한을 먼저 적용한다.
