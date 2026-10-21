# Spring RAG 챗봇

로컬 기동·점검·백업·장애 대응 절차는 [운영 런북](runbooks/SpringRagLocalV0_1_0.md)을 참고하세요.

Spring Boot 4 + Spring AI 2.0.1 백엔드, React + Vite 화면, PostgreSQL/pgvector로 구성한 문서 기반 챗봇입니다. 화면은 딥블루 색상, 대화 목록, 본문, 참고 자료 패널로 구성했습니다.

## 현재 범위

- 로컬 `admin` / `user` 계정. 관리자는 **공용 문서**를 등록·삭제하고 웹 URL을 수집합니다. 사용자는 **내 문서**를 등록·삭제합니다.
- 파일: TXT, PDF, DOC, DOCX, HTML/HTM (최대 20MB). OCR은 없습니다.
- URL: 관리자가 입력한 시작 페이지와 **같은 호스트·같은 경로 하위**의 HTML 및 링크된 TXT/PDF/DOC/DOCX를 `maxPages`(1~100, 기본 100)까지 수집합니다. 동일 URL의 동일 내용은 건너뛰고, 내용이 달라지면 재색인합니다.
- Extract → Transform → Load: TXT/PDF/Word/HTML 텍스트를 추출하고, `TokenTextSplitter`로 나눈 뒤, `text-embedding-3-small` 임베딩을 pgvector에 적재합니다. 청크에는 문서 ID, 범위, 소유자, 제목, 출처 URL, 카테고리, 순서와 가능한 경우 HTML 구간 또는 PDF 페이지가 들어갑니다. 등록 시 카테고리 1~80자를 입력합니다.
- 인증된 독립 검색 API는 공용 문서 또는 본인 문서에 한해 pgvector 후보 Top-10(유사도 기준 0.3)을 찾고, 로컬 `Qwen/Qwen3-Reranker-0.6B`로 재정렬합니다. 후보와 재정렬 결과의 순위·점수를 각각 반환합니다.
- 대화는 **공용 문서 / 내 문서** 중 하나의 검색 범위를 선택해 시작합니다. 대화가 시작된 뒤 범위를 바꾸면 새 대화로 전환됩니다. 검색 필터와 대화 소유권은 서버에서 검사합니다.
- 생성 모델이 설정된 경우, 이전 질문을 고려해 검색어를 정리하고, 검색된 청크만 근거로 답변하며, 사용한 문서를 오른쪽 패널에 표시합니다. `MessageChatMemoryAdvisor` + JDBC 대화 메모리를 사용합니다. 화면의 전체 대화 기록은 별도 테이블에 저장합니다.

## 실행 준비

Java 17+, Maven, Node 20+, Docker가 필요합니다. 문서 적재와 검색에는 OpenAI 임베딩 API 키가 필요합니다. 생성용 채팅 모델은 아직 결정되지 않아 **대화 질문 요청은 503**을 반환합니다. 독립 검색 API는 생성 모델 없이 사용할 수 있지만, 결과가 있을 때 로컬 Qwen 리랭커가 실행 중이어야 합니다. 생성 모델을 고를 때 해당 Spring AI 모델 스타터와 설정을 추가하세요.

```bash
docker compose up -d db
export APP_ADMIN_PASSWORD='change-this-admin-password'
export APP_USER_PASSWORD='change-this-user-password'
export OPENAI_API_KEY='your-embedding-api-key'
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

## API

요청에는 HTTP Basic 인증을 사용합니다. 프런트엔드는 자격 증명을 브라우저 메모리에만 보관해 새로고침 시 다시 로그인합니다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/me` | 로그인 확인 |
| GET/POST | `/api/documents` | 선택한 범위의 문서 목록 / 파일 업로드 (`file`, `category` multipart) |
| DELETE | `/api/documents/{id}` | 본인이 관리할 수 있는 문서 삭제 |
| POST | `/api/admin/crawl` | 관리자의 URL 수집 (`{"url":"https://...","category":"Spring AI docs","maxPages":1}`) |
| GET | `/api/search?query=...&scope=SHARED` | 인증된 벡터 후보 및 Qwen 재정렬 결과. `PRIVATE`는 로그인한 본인 문서만 검색 |
| GET/POST | `/api/conversations` | 내 대화 목록 / 대화 생성 (`{"scope":"SHARED"}` 또는 `PRIVATE`) |
| GET/POST | `/api/conversations/{id}/messages` | 대화 메시지 조회 / 질문 (`{"text":"..."}`) |

## 데이터 흐름

```text
업로드·URL → 리더(TXT/PDF/Word/HTML) → 청크 분할 → OpenAI 임베딩 → pgvector
단일 검색 질문 + 인증된 범위 → pgvector Top-10/0.3 → Qwen 리랭킹 → 후보·재정렬 근거
향후 대화 질문 → 이전 질문 반영 → 같은 검색·리랭킹 → 생성 모델 → 답변 + 근거
```

PostgreSQL에는 문서 카탈로그, 청크 ID, 대화 목록/메시지와 Spring AI 채팅 메모리를 저장합니다. 파일 원본은 `UPLOAD_DIR`에, 검색용 텍스트와 메타데이터는 pgvector에 저장합니다.

## MVC·레이어드 구조

```text
React 화면(View) → controller/ApiController → service/ → dao/ → PostgreSQL
                              ↕              ↘ Spring AI VectorStore(pgvector)
                         dto/ 요청·응답
service/Reranker ← infrastructure/QwenReranker(로컬 모델 서버)
```

`controller`는 HTTP·인증 주체와 DTO를 처리하고, `service`는 수집·ETL·검색·대화 규칙을 맡습니다. `dao`의 `KnowledgeDao`와 `ChatDao`가 JDBC SQL을 담당합니다. JPA를 사용하지 않으므로 별도 Entity 클래스는 없으며, `dto`의 record가 API 요청·응답 형식을 정의합니다. pgvector 읽기·쓰기는 Spring AI의 `VectorStore`를 서비스에서 사용합니다.

## 확인

```bash
mvn test
docker info && mvn -Dtest=PgvectorSearchIT test
cd frontend && npm run build
cd .. && python3 -m unittest discover -s reranker -p 'test_*.py'
python3 -m unittest discover -s evaluation -p 'test_*.py'
python3 evaluation/evaluate.py
```

`evaluation/questions.json`에는 [ETL](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html), [PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html), [Chat Memory](https://docs.spring.io/spring-ai/reference/api/chat-memory.html), [Chat Client](https://docs.spring.io/spring-ai/reference/api/chatclient.html) 각 한 쪽에 대한 질문 16개가 있습니다. 기본 평가 명령은 질문 세트만 검사하고 품질을 **미측정**으로 표시합니다. 실제 측정은 관리자 비밀번호를 `EVAL_ADMIN_PASSWORD`에 설정하고 `python3 evaluation/evaluate.py --execute`로 시작합니다. 이 명령은 URL별 `maxPages=1`로 네 번 수집하고 16개 검색을 호출하며 결과를 `evaluation/results/<UTC>/`에 저장합니다. `config.json`, `ingest.jsonl`, `search.jsonl`, `summary.json`을 함께 검토하세요. 수집은 유료 OpenAI 임베딩을 호출하므로 실행 전에 예상 청크·API 호출 상한, 현재 가격과 비용, 중단 조건을 확인하고 승인받아야 합니다. 리랭커 오류는 503으로 기록되며 성공한 검색 지표에 섞지 않습니다.

## 첫 버전 제한

- 생성용 채팅 모델은 미정입니다. 쿼리 리라이팅의 실동작과 답변 품질 평가는 생성 모델 선택 후 검증해야 합니다. 키워드/요약 메타데이터 자동 생성과 OCR도 아직 없습니다.
- URL 수집은 동기식이며 한 번에 최대 100개입니다. `robots.txt` 정책 처리, 예약 재수집, 작업 큐, 크롤링 진행률은 후속 작업입니다. 외부 URL을 등록하는 관리자 권한은 신뢰된 운영자에게만 부여하세요.
- 현재 계정은 고정된 `admin`과 `user` 두 개입니다. 여러 사용자의 가입, 권한 위임, 운영 환경용 감사 로그는 포함하지 않았습니다.
