# 작업 규칙

이 파일은 저장소의 코드, 문서, 테스트, Git 작업에 적용한다. 사용자의 직접 지시가 있으면 그 지시를 우선한다.

## 현재 기술 스택

- 백엔드: Java 17, Spring Boot 4.0.0, Spring AI 2.0.1, Spring MVC, Spring Security, Spring JDBC, Spring AI 문서 리더(PDF·Tika·JSoup), Maven.
- 프런트엔드: React 19, TypeScript 5, Vite 7.
- 데이터: PostgreSQL 17, pgvector, Spring AI JDBC Chat Memory.
- AI: OpenAI `text-embedding-3-small`, 로컬 `Qwen/Qwen3-Reranker-0.6B`(Python, sentence-transformers). 생성용 채팅 모델은 미정.
- 로컬 실행·검증: Docker Compose, JUnit, Testcontainers, Python unittest.

이 목록은 현재 구현의 기록이며 기술 도입을 제한하지 않는다. 새 기술을 추가하거나 버전을 바꾸면 실제 설정과 이 목록을 함께 갱신한다.

## 모듈과 패키지

- 프런트엔드와 백엔드는 각각 `frontend/`, `backend/` 모듈로 분리한다.
- 백엔드 코드는 서비스 경계(예: `chat`, `knowledge`, `retrieval`, `crawl`)별로 묶고, 각 서비스 안에서 필요한 `controller`, `dto`, `service`, `dao` 역할을 구분한다. 공통 설정과 외부 연동 코드는 공유가 필요한 범위에만 둔다.
- Controller는 HTTP·인증 주체·DTO를, Service는 비즈니스 규칙을, DAO는 데이터 접근을 담당한다. 프런트엔드의 화면과 API 호출 코드는 백엔드 패키지에 섞지 않는다.
- 현재 백엔드는 저장소 루트 `src/`의 계층별 패키지에 있다. 위 구조로 전환하는 작업에서는 기존 API와 데이터 동작을 보존하고 관련 테스트·문서를 함께 옮긴다.

## 문서

- API 변경에는 엔드포인트, 인증·권한, 요청·응답 DTO, 오류 형식을 명세한다. 데이터 모델 변경에는 테이블·컬럼·관계, 벡터 메타데이터, 소유권·범위를 문서화한다.
- 코드·설정·운영 절차를 바꿀 때 `docs/`, `runbooks/`, README에서 관련된 Markdown 파일을 찾아 같은 변경에 갱신한다. 관련 문서가 없으면 작성한다. 문서와 구현이 다른 상태로 작업을 끝내지 않는다.
- 런북은 저장소 루트의 `runbooks/` 아래에 `CheckoutErrorBudgetBurnFast.md`처럼 목적을 알 수 있는 이름으로 두고, 버전별 파일로 관리한다. 버전별 실행·복구 절차가 바뀌면 해당 런북을 갱신한다.

## 구현·검증·보안

- 비즈니스 로직을 추가하거나 변경할 때 먼저 실패하는 단위 테스트를 작성하고, 구현 후 통과를 확인한다. DB·벡터 검색·HTTP 경계처럼 단위 테스트만으로 확인하기 어려운 부분은 통합 테스트도 실행한다.
- 로그는 구조화된 필드로 남기고 요청의 `traceId`를 생성·전파해 관련 로그를 연결한다. 인증 정보, 비밀번호, API 키, 토큰, 문서 원문 등 민감 정보는 로그에 쓰지 않고 필요한 식별자는 마스킹한다.
- 비밀번호·API 키·토큰을 코드나 설정에 하드코딩하지 않는다. 환경 변수 또는 로컬 비밀 파일로 주입하고, 비밀 파일과 실제 값을 Git에 추가하거나 푸시하지 않는다. 커밋 전에 스테이징된 변경에서 비밀값을 확인한다.
- 현재 `compose.yaml`과 `application.yml`의 개발용 DB 비밀번호 기본값은 이 규칙에 맞게 별도 정리해야 한다.

## Git

- `feat`, `fix`, `docs`, `refactor`, `test`, `chore`는 브랜치명이 아니라 커밋 메시지의 타입으로 사용한다. 브랜치명에는 이 접두사를 강제하지 않는다.
- 커밋 메시지는 `feat: 기능 변경`, `fix: 오류 수정`, `docs: 문서 수정`처럼 영문 타입 뒤에 콜론과 한글 설명을 쓴다. 하나의 커밋에는 한 가지 목적의 변경만 넣고, 그 목적에 필요한 코드·테스트·문서만 함께 포함한다.
- 관련 없는 파일이나 로컬 IDE 설정을 스테이징하지 않는다. 커밋·푸시 전 상태와 변경 파일, 테스트 결과를 확인한다.
