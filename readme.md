# Spring RAG 챗봇

Spring Boot 4 + Spring AI 2.0.1 백엔드, React + Vite 화면, PostgreSQL/pgvector로 구성한 문서 기반 챗봇입니다. 화면은 딥블루 색상, 대화 목록, 본문, 참고 자료 패널로 구성했습니다.

## 현재 범위

- 로컬 `admin` / `user` 계정. 관리자는 **공용 문서**를 등록·삭제하고 웹 URL을 수집합니다. 사용자는 **내 문서**를 등록·삭제합니다.
- 파일: TXT, PDF, DOC, DOCX, HTML/HTM (최대 20MB). OCR은 없습니다.
- URL: 관리자가 입력한 시작 페이지와 **같은 호스트·같은 경로 하위**의 HTML 및 링크된 TXT/PDF/DOC/DOCX를 최대 100개까지 수집합니다. 동일 URL의 동일 내용은 건너뛰고, 내용이 달라지면 재색인합니다.
- Extract → Transform → Load: Spring AI 문서 리더로 텍스트와 페이지 메타데이터를 추출하고, `TokenTextSplitter`로 나눈 뒤, `text-embedding-3-small` 임베딩을 pgvector에 적재합니다. 청크에는 문서 ID, 범위, 소유자, 제목, 출처 URL이 들어갑니다.
- 대화는 **공용 문서 / 내 문서** 중 하나의 검색 범위를 선택해 시작합니다. 대화가 시작된 뒤 범위를 바꾸면 새 대화로 전환됩니다. 검색 필터와 대화 소유권은 서버에서 검사합니다.
- 생성 모델이 설정된 경우, 이전 질문을 고려해 검색어를 정리하고, 검색된 청크만 근거로 답변하며, 사용한 문서를 오른쪽 패널에 표시합니다. `MessageChatMemoryAdvisor` + JDBC 대화 메모리를 사용합니다. 화면의 전체 대화 기록은 별도 테이블에 저장합니다.

## 실행 준비

Java 17+, Maven, Node 20+, Docker가 필요합니다. OpenAI 임베딩 API 키가 필요합니다. 생성용 채팅 모델은 아직 결정되지 않았으므로 **기본 상태에서는 문서 적재와 검색 준비까지 가능하고, 질문 요청은 503을 반환**합니다. 생성 모델을 고를 때 해당 Spring AI 모델 스타터와 설정을 추가하세요. 실제 모델 호출 비용은 키 소유자가 부담합니다.

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

## API

요청에는 HTTP Basic 인증을 사용합니다. 프런트엔드는 자격 증명을 브라우저 메모리에만 보관해 새로고침 시 다시 로그인합니다.

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/me` | 로그인 확인 |
| GET/POST | `/api/documents` | 선택한 범위의 문서 목록 / 파일 업로드 |
| DELETE | `/api/documents/{id}` | 본인이 관리할 수 있는 문서 삭제 |
| POST | `/api/admin/crawl` | 관리자의 URL 및 하위 페이지 수집 (`{"url":"https://..."}`) |
| GET/POST | `/api/conversations` | 내 대화 목록 / 대화 생성 (`{"scope":"SHARED"}` 또는 `PRIVATE`) |
| GET/POST | `/api/conversations/{id}/messages` | 대화 메시지 조회 / 질문 (`{"text":"..."}`) |

## 데이터 흐름

```text
업로드·URL → 리더(TXT/PDF/Word/HTML) → 청크 분할 → OpenAI 임베딩 → pgvector
질문 + 대화 범위 → 이전 질문 반영 → pgvector 필터 검색 → 생성 모델 → 답변 + 근거
```

PostgreSQL에는 문서 카탈로그, 청크 ID, 대화 목록/메시지와 Spring AI 채팅 메모리를 저장합니다. 파일 원본은 `UPLOAD_DIR`에, 검색용 텍스트와 메타데이터는 pgvector에 저장합니다.

## 확인

```bash
mvn test
cd frontend && npm run build
```

## 첫 버전 제한

- 생성용 채팅 모델과 실제 수집 시작 URL은 미정입니다. 키워드/요약 메타데이터 자동 생성과 OCR도 아직 없습니다.
- URL 수집은 동기식이며 한 번에 최대 100개입니다. `robots.txt` 정책 처리, 예약 재수집, 작업 큐, 크롤링 진행률은 후속 작업입니다. 외부 URL을 등록하는 관리자 권한은 신뢰된 운영자에게만 부여하세요.
- 현재 계정은 고정된 `admin`과 `user` 두 개입니다. 여러 사용자의 가입, 권한 위임, 운영 환경용 감사 로그는 포함하지 않았습니다.
