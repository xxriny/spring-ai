# Spring RAG 챗봇

로컬 기동·점검·백업·장애 대응 절차는 [운영 런북](runbooks/SpringRagLocalV0_1_0.md)을 참고하세요.

Spring Boot 4 + Spring AI 2.0.1 백엔드, React + Vite 화면, PostgreSQL/pgvector/pg_search로 구성한 문서 기반 챗봇입니다. 화면은 딥블루 색상, 대화 목록, 본문, 참고 자료 패널로 구성했습니다.

## 현재 범위

- 로컬 `admin` / `user` 계정. 관리자는 **공용 문서**를 등록·삭제하고 웹 URL을 수집합니다. 사용자는 **내 문서**를 등록·삭제합니다.
- 파일: TXT, PDF, DOC, DOCX, HTML/HTM (최대 20MB). 반복 머리말·꼬리말과 표를 분리한 뒤 본문과 표 행이 없는 PDF 페이지만 로컬 PaddleOCR 한국어 PP-OCRv5로 인식합니다.
- URL: 관리자가 입력한 시작 페이지와 **같은 호스트·같은 경로 하위**의 HTML 및 링크된 TXT/PDF/DOC/DOCX를 `maxPages`(1~100, 기본 100)까지 수집합니다. 동일 URL의 동일 내용은 건너뛰고, 내용이 달라지면 재색인합니다.
- Extract → Transform → Load: TXT/Word/HTML 텍스트를 추출하고, PDF는 로컬 pdfplumber 서비스가 반복 머리말·꼬리말을 제거하고 표 행을 Markdown으로 별도 추출합니다. 텍스트가 없는 PDF 페이지만 OCR로 읽습니다. 본문은 `TokenTextSplitter`로 나누고 같은 페이지·구간의 인접 청크에 이전 청크의 최대 100토큰을 겹칩니다. 표 행에는 오버랩을 적용하지 않습니다. 청크당 최대 800토큰을 유지하고 Ollama `qwen3-embedding:8b`의 1536차원 임베딩을 pgvector에 적재합니다. 공통 메타데이터는 `documentId`, `scope`, `ownerId`, `title`, `source`, `category`, `chunkOrder`, `embeddingModel`입니다. PDF에는 `page_number`와 `contentType=BODY|TABLE_ROW`를, 표 행에는 `tableIndex`, `rowIndex`, `tableExtractionStatus`를 추가합니다. 등록 시 카테고리 1~80자를 입력합니다.
- 인증된 독립 검색 API는 공용 문서 또는 본인 문서에 한해 pgvector Top-10(유사도 기준 0.3)과 BM25 Top-10을 조회합니다. 청크 ID로 중복 제거 후 RRF(k=60) 상위 10개를 로컬 `Qwen/Qwen3-Reranker-0.6B`로 재정렬합니다. 상위 3개 청크의 같은 문서·페이지 앞뒤 청크를 붙이고 오버랩을 제거해 passage당 최대 1,200토큰의 근거를 반환합니다.
- 대화는 **공용 문서 / 내 문서** 중 하나의 검색 범위를 선택해 시작합니다. 대화가 시작된 뒤 범위를 바꾸면 새 대화로 전환됩니다. 검색 필터와 대화 소유권은 서버에서 검사합니다.
- 생성 모델이 설정된 경우, 이전 질문을 고려해 검색어를 정리하고, 최종 passage만 근거로 답변하며, 사용한 문서를 오른쪽 패널에 표시합니다. `MessageChatMemoryAdvisor` + JDBC 대화 메모리를 사용합니다. 화면의 전체 대화 기록은 별도 테이블에 저장합니다. 현재는 생성 모델이 없어 질문 원문 하나로 검색하며 MultiQueryExpander는 아직 연결하지 않았습니다.

## 실행 준비

Java 17+, Maven, Node 20+, Docker, Ollama가 필요합니다. 문서 적재와 검색은 로컬 Qwen3 임베딩을 사용하므로 OpenAI API 키가 필요하지 않습니다. `ollama pull qwen3-embedding:8b`로 모델을 받은 뒤 Ollama 서버를 실행하세요. 생성용 채팅 모델은 아직 결정되지 않아 **대화 질문 요청은 503**을 반환합니다. 독립 검색 API는 생성 모델 없이 사용할 수 있지만, 결과가 있을 때 로컬 Qwen 리랭커가 실행 중이어야 합니다. 모든 PDF 등록에는 로컬 PDF 추출 서버가 필요하며, 스캔 페이지에는 같은 서버의 OCR 경로도 사용합니다. 생성 모델을 고를 때 해당 Spring AI 모델 스타터와 설정을 추가하세요.

```bash
docker compose up -d db
export APP_ADMIN_PASSWORD='change-this-admin-password'
export APP_USER_PASSWORD='change-this-user-password'
mvn spring-boot:run
```

다른 터미널에서:

```bash
cd frontend
npm install
npm run dev
```

브라우저에서 `http://localhost:5173`을 열고 `admin` 또는 `user`로 로그인합니다. DB 기본값은 `localhost:5432`, DB/사용자/비밀번호 모두 `springrag`입니다. 변경할 때 `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`를 설정하세요. 업로드 위치는 `UPLOAD_DIR`(기본 `./data/uploads`)입니다. 이 계정 방식은 로컬 첫 버전용이며, 공개 배포 전에는 HTTPS와 실제 사용자 인증 체계가 필요합니다.

### 로컬 리랭커

Python 환경에 `pip install -r reranker/requirements.txt`로 의존성을 설치하고 `python reranker/server.py`로 실행합니다. 서버는 `127.0.0.1:8091`에만 바인딩하고 시작할 때 [Qwen 모델](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B)을 내려받아 로드합니다. 첫 실행 전에 모델 다운로드 용량, 사용 가능한 메모리와 CPU/GPU를 확인하세요. 다른 주소를 사용할 때 `RERANKER_URL`을 설정합니다. Qwen의 원시 점수는 확률이 아니며 0.3 기준은 pgvector 후보 검색에만 적용됩니다.

### 로컬 PDF 추출·OCR

별도 Python 환경에 `pip install -r ocr/requirements.txt`로 pdfplumber·PaddleOCR 의존성을 설치하고 `python ocr/server.py`로 실행합니다. 서버는 `127.0.0.1:8092`에만 바인딩하며 한국어 PP-OCRv5 모델을 첫 시작에 다운로드합니다. 설치 전에 [PaddleOCR 설치 안내](https://www.paddleocr.ai/main/en/version3.x/installation.html)에서 운영체제와 Python 버전에 맞는 PaddlePaddle 런타임을 확인하세요. 다른 주소를 사용할 때 `PDF_EXTRACTION_URL`(`/pdf/extract`)과 `OCR_URL`(`/ocr`)을 설정합니다. 일반 텍스트 PDF는 OCR을 호출하지 않지만 PDF 추출 서버가 필요합니다. 추출·OCR이 실패하면 등록은 실패하며 기존 URL 문서의 색인은 유지됩니다.

## API

요청에는 HTTP Basic 인증을 사용합니다. 프런트엔드는 자격 증명을 브라우저 메모리에만 보관해 새로고침 시 다시 로그인합니다.

내부 PDF 추출 요청은 `PDF_EXTRACTION_URL`의 `/pdf/extract`로 `application/pdf` 원본 바이트(최대 20MB)를 보냅니다. 응답은 `pages[]`의 `pageNumber`, `body`, `ocrRequired`, `tables[]`와 각 표의 `tableIndex`, `rows[]`(`rowIndex`, `headers`, `cells`, `markdown`, `extractionStatus`)입니다. 형식 오류나 서비스 장애는 문서 등록 오류가 되며, 원본과 표 내용은 오류 로그에 기록하지 않습니다. 이 경로는 로컬 루프백 서비스용이며 공개 API가 아닙니다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/me` | 로그인 확인 |
| GET/POST | `/api/documents` | 선택한 범위의 문서 목록 / 파일 업로드 (`file`, `category` multipart) |
| DELETE | `/api/documents/{id}` | 본인이 관리할 수 있는 문서 삭제 |
| POST | `/api/admin/crawl` | 관리자의 URL 수집 (`{"url":"https://...","category":"Spring AI docs","maxPages":1}`) |
| GET | `/api/search?query=...&scope=SHARED` | 인증된 벡터·BM25·RRF 후보, Qwen 재정렬 및 최종 passage. `PRIVATE`는 로그인한 본인 문서만 검색 |
| GET/POST | `/api/conversations` | 내 대화 목록 / 대화 생성 (`{"scope":"SHARED"}` 또는 `PRIVATE`) |
| GET/POST | `/api/conversations/{id}/messages` | 대화 메시지 조회 / 질문 (`{"text":"..."}`) |

## 데이터 흐름

```text
업로드·URL → 리더(TXT/Word/HTML, PDF 본문·Markdown 표 행 분리, 텍스트 없는 PDF 페이지만 OCR) → 청크 분할 → Ollama Qwen3-Embedding-8B(1536차원) → pgvector
단일 검색 질문 + 인증된 범위 → pgvector Top-10/0.3 + pg_search BM25 Top-10 → RRF Top-10 → Qwen 리랭킹 → 같은 문서·페이지 앞뒤 청크 → Top-3 passage/최대 3,600토큰
향후 대화 질문 → 이전 질문 반영·MultiQuery 확장 → 같은 검색·리랭킹·passage → 생성 모델 → 답변 + 근거
```

PostgreSQL에는 문서 카탈로그, 청크 ID, 대화 목록/메시지와 Spring AI 채팅 메모리를 저장합니다. 파일 원본은 `UPLOAD_DIR`에, 검색용 텍스트·임베딩·메타데이터는 단일 `vector_store` 테이블에 저장합니다. `pg_search` BM25 인덱스는 이 테이블의 기존 행까지 색인합니다. 상세 API·데이터 모델은 [하이브리드 검색 명세](docs/HybridRetrieval.md)를 참고하세요.
기존 OpenAI 임베딩 문서는 새 모델과 혼합할 수 없습니다. `vector_store`에 `embeddingModel=qwen3-embedding:8b/1536` 이외의 청크가 있으면 앱 시작을 중단합니다. DB·업로드 원본을 백업하고 [재색인 절차](runbooks/SpringRagLocalV0_1_0.md)를 수행하세요. 반복 머리말 제거·표 행 분리·오버랩 변경도 기존 문서에는 자동 적용되지 않습니다.

## MVC·레이어드 구조

```text
React 화면(View) → controller/ApiController → service/ → dao/ → PostgreSQL
                              ↕              ↘ Spring AI VectorStore(pgvector)
                         dto/ 요청·응답
service/Reranker ← infrastructure/QwenReranker(로컬 모델 서버)
service/OcrClient ← infrastructure/PaddleOcrClient(로컬 OCR 서버)
service/PdfExtractionClient ← infrastructure/LocalPdfExtractionClient(로컬 PDF 추출 서버)
```

`controller`는 HTTP·인증 주체와 DTO를 처리하고, `service`는 수집·ETL·검색·대화 규칙을 맡습니다. `dao`의 `KnowledgeDao`, `ChatDao`, `JdbcRetrievalDao`가 JDBC SQL을 담당합니다. JPA를 사용하지 않으므로 별도 Entity 클래스는 없으며, `dto`의 record가 API 요청·응답 형식을 정의합니다. pgvector 읽기·쓰기는 Spring AI의 `VectorStore`를 서비스에서 사용합니다.

## 확인

```bash
mvn test
docker info && mvn -Dtest=PgvectorSearchIT,JdbcRetrievalDaoIT test
cd frontend && npm run build
cd .. && python3 -m unittest discover -s reranker -p 'test_*.py'
python3 -m unittest discover -s ocr -p 'test_*.py'
python3 -m unittest discover -s evaluation -p 'test_*.py'
python3 evaluation/evaluate.py
```

`evaluation/questions.json`에는 [ETL](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html), [PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html), [Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html), [Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclient.html) 각 한 쪽에 대한 질문 16개가 있습니다. 기본 평가 명령은 질문 세트만 검사하고 품질을 **미측정**으로 표시합니다. 실제 측정은 관리자 비밀번호를 `EVAL_ADMIN_PASSWORD`에 설정하고 `python3 evaluation/evaluate.py --execute`로 시작합니다. 이 명령은 URL별 `maxPages=1`로 네 번 수집하고 16개 검색을 호출하며 결과를 `evaluation/results/<UTC>/`에 저장합니다. `config.json`, `ingest.jsonl`, `search.jsonl`, `summary.json`에서 벡터·BM25·결합·재정렬·최종 passage의 Hit@k와 MRR을 비교하세요. 외부 임베딩 요금은 없지만 모델 다운로드·로컬 연산 자원을 사용합니다. 리랭커 오류는 503으로 기록되며 성공한 검색 지표에 섞지 않습니다.

## 첫 버전 제한

- 생성용 채팅 모델은 미정입니다. 쿼리 리라이팅의 실동작과 답변 품질 평가는 생성 모델 선택 후 검증해야 합니다. 키워드/요약 메타데이터 자동 생성은 아직 없습니다. PDF에서 표 구조를 확정할 수 없으면 해당 텍스트를 본문에 보존합니다. OCR은 정리 후 텍스트가 없는 PDF 페이지에만 적용하며 Word/HTML 이미지와 표 구조 인식은 포함하지 않습니다.
- URL 수집은 동기식이며 한 번에 최대 100개입니다. `robots.txt` 정책 처리, 예약 재수집, 작업 큐, 크롤링 진행률은 후속 작업입니다. 외부 URL을 등록하는 관리자 권한은 신뢰된 운영자에게만 부여하세요.
- 현재 계정은 고정된 `admin`과 `user` 두 개입니다. 여러 사용자의 가입, 권한 위임, 운영 환경용 감사 로그는 포함하지 않았습니다.
