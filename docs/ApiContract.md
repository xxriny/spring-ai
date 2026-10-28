# HTTP API 계약 (v0.1.0)

## 공통 계약

- 로컬 기본 주소는 `http://localhost:8080`이다. 기존 `/api/**`의 `/api/health` 외 경로와 `/v3/api-docs`, `/swagger-ui.html`에는 HTTP Basic 인증이 필요하다. 관리자 전용 경로는 `/api/admin/**`이다. GCP 프로필에서만 제공하는 `/api/public/**`는 [공개 체험 계약](PublicDemo.md)을 따른다.
- `SHARED`는 공용 문서, `PRIVATE`는 인증된 본인 문서다. 개인 문서 소유자는 요청값이 아닌 로그인 계정에서 결정한다. 대화 범위는 생성할 때 고정한다.
- 검증·소유권 오류는 `400 {"error":"설명"}`, 인증 실패는 `401`, 관리자 권한 부족은 `403`이다. PDF 추출·OCR·리랭커·생성 모델을 사용할 수 없으면 해당 경로에서 `503 {"error":"설명"}`을 반환한다. 모든 API 응답의 `X-Trace-Id`로 요청 로그를 연결한다.
- 실행 중 생성된 OpenAPI JSON은 `/v3/api-docs`, Swagger UI는 `/swagger-ui.html`에 있다.

## 엔드포인트

| 메서드·경로 | 인증·권한 | 요청 | 성공 응답 | 주요 오류 |
| --- | --- | --- | --- | --- |
| `GET /api/health` | 없음 | 없음 | `200 {"status":"UP"}` | — |
| `GET /api/me` | 로그인 | 없음 | `200 {"username":"user","admin":false}` | 401 |
| `GET /api/documents` | 로그인 | `scope=SHARED` 또는 `PRIVATE`, 기본 `SHARED` | `200 DocumentDto[]` | 400, 401 |
| `POST /api/documents` | 로그인; 관리자는 공용, 사용자는 개인으로 등록 | multipart `file`, `category` | `200 DocumentDto` | 400, 401, 503 |
| `DELETE /api/documents/{id}` | 관리자 공용 또는 본인 개인 문서 | 문서 UUID | `204` 본문 없음 | 400, 401 |
| `POST /api/admin/crawl` | 관리자 | `CrawlRequest` JSON | `200 CrawlResultDto` | 400, 401, 403 |
| `GET /api/search` | 로그인 | `query` 필수, `scope`는 `SHARED` 또는 `PRIVATE`, 기본 `SHARED` | `200 SearchResultDto` | 400, 401, 503 |
| `GET /api/conversations` | 로그인 | 없음 | `200 ConversationDto[]` | 401 |
| `POST /api/conversations` | 로그인 | `{"scope":"SHARED"}` 또는 `PRIVATE` | `200 ConversationDto` | 400, 401 |
| `GET /api/conversations/{id}/messages` | 본인 대화 | 대화 UUID | `200 MessageDto[]` | 400, 401 |
| `POST /api/conversations/{id}/messages` | 본인 대화 | `{"text":"질문"}` | `200 AnswerDto` | 400, 401, 503 |
| `POST /api/conversations/{id}/messages/stream` | 본인 대화 | `{"text":"질문"}` | `200 application/x-ndjson` | 시작 전 400, 401, 503 |

파일은 TXT/PDF/DOC/DOCX/HTML/HTM, 최대 20MB다. 카테고리는 1~80자이며 질문은 공백만 있는 문자열을 허용하지 않고 1~4,000자다. `CrawlRequest`는 `{"url":"https://...","category":"Spring AI docs","maxPages":1}` 형태이며 `maxPages`는 1~100, 생략 시 100이다.

## DTO와 스트림

- `DocumentDto`: `id`, `title`, `scope`, `sourceUrl`(업로드 시 `null`), `category`, `status`.
- `CrawlResultDto`: `indexed`, `skipped`, `errors`(페이지별 오류 문자열 배열).
- `ConversationDto`: `id`, `title`, `scope`. `MessageDto`: `role`(`user` 또는 `assistant`), `body`, `sources`(`SourceDto[]`).
- `AnswerDto`: `conversationId`, `answer`, `sources`. `SourceDto`: `title`, `url`, `excerpt`, `documentId`, `page`(PDF 외에는 `null`).
- `SearchResultDto`: `vectorCandidates`, `bm25Candidates`, `candidates`, `reranked`, `passages` 배열. 항목별 점수·순위·출처 필드와 검색 단계는 [하이브리드 검색 명세](HybridRetrieval.md)에 있다.
- 스트림은 줄마다 JSON 이벤트 하나다. `sources` 이벤트가 먼저 오고, `delta` 이벤트의 `text`를 이어 붙이며, `done`이 저장 완료를 알린다. 예: `{"type":"sources","text":null,"sources":[]}` → `{"type":"delta","text":"답변","sources":null}` → `{"type":"done","text":null,"sources":null}`. 완료 전에 연결이 끊기거나 생성이 실패하면 `done`이 없고 화면용 답변은 저장하지 않는다. 스트림 시작 후 오류는 HTTP 상태를 바꿀 수 없으므로 클라이언트는 `done` 누락을 실패로 처리해야 한다.

내부 PDF 추출 서비스의 `/pdf/extract`는 공개 API가 아니다. Spring Boot가 `application/pdf` 원본 바이트(최대 20MB)를 보내고 `pages[]`의 `pageNumber`, `body`, `ocrRequired`, `tables[]`와 각 표의 `tableIndex`, `rows[]`(`rowIndex`, `headers`, `cells`, `markdown`, `extractionStatus`)를 받는다. 서버 연결 실패·HTTP 오류 시 등록은 `503 {"error":"PDF 추출 서비스를 사용할 수 없습니다."}`로 실패하며 원문은 오류 로그에 남기지 않는다.
