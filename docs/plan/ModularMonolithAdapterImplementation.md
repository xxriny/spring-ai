# Modular Monolith with Adapters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 동작을 유지하면서 백엔드를 서비스별 모듈형 모놀리스와 포트·어댑터 경계로 전환한다.

**Architecture:** 단일 Spring Boot 프로세스와 DB를 유지한다. `chat`, `knowledge`, `retrieval`, `crawl`을 업무별 패키지로 묶고 각 패키지 안의 MVC 역할을 유지한다. 서비스는 필요한 포트에 의존하고 JDBC·VectorStore·외부 HTTP 구현은 DAO 또는 `infrastructure` 어댑터에 둔다.

**Tech Stack:** Java 17, Spring Boot 4.0.0, Spring AI 2.0.1, Maven, JUnit, Testcontainers, React 19, Python unittest.

**Spec:** [ADR 0003](../adr/0003-modular-monolith-with-adapters.md)

## Global Constraints

- 공개 API·OpenAPI, 인증·권한, 스트림 이벤트, DB 스키마와 기존 데이터, 검색 결과를 유지한다.
- 기존 `UPLOAD_DIR=./data/uploads`가 저장소 루트에서 같은 파일을 가리키도록 한다.
- 인프라 실행 파일은 최상위 `infrastructure/`, Java 외부 연동 구현은 `dev.springrag.infrastructure`에 둔다.
- 수정한 공개 API에는 JavaDoc, 수정한 JUnit 테스트에는 한국어 `@DisplayName`을 쓴다.
- 관련 `docs/`, `runbooks/`, README를 같은 변경에 갱신한다.

## Review Focus

- Maven 실행 위치를 바꿔도 테스트 fixture와 기존 업로드 경로가 유지되는가? Task 1에서 경로 테스트와 빌드로 확인한다.
- `PRIVATE` 문서와 대화 소유권이 패키지 이동 후에도 유지되는가? Task 2 통합 테스트로 확인한다.
- 벡터와 BM25가 같은 문서·소유권 필터를 유지하는가? Task 3 검색 통합 테스트로 확인한다.
- 채팅 스트림의 `sources`, `delta`, `done` 및 실패 시 저장 금지가 유지되는가? Task 2 스트림 테스트로 확인한다.
- 외부 연동 장애가 기존 503 계약으로 매핑되는가? Task 3 API 테스트로 확인한다.

---

### Task 1: 백엔드 빌드 모듈과 실행 경로

**Files:** 루트 `pom.xml`, `backend/pom.xml`, `backend/src/**`, `README.md`, `runbooks/SpringRagLocalV0_1_0.md`, 기존 경로 의존 테스트.

**Interfaces:** 루트의 `mvn test`를 유지하고 백엔드 실행은 루트에서 `mvn -pl backend spring-boot:run`으로 명시한다. `backend/`가 실제 Spring Boot 모듈이다.

- [x] 기존 Maven·프런트엔드·Python 테스트 기준선을 실행한다.
- [x] 빌드·fixture 경로를 확인하는 실패 테스트를 먼저 작성하고 실패를 본다.
- [x] `pom.xml`과 `src/`를 `backend/`로 옮기고 루트 Maven 진입점을 제공한다.
- [x] 테스트·빌드와 기존 업로드 경로 확인을 통과시킨다.
- [x] README·런북의 실행 명령을 갱신한다.

### Task 2: 업무 모듈 패키지 이동

**Files:** `backend/src/main/java/dev/springrag/{chat,knowledge,retrieval,crawl,common,infrastructure}/**`, `backend/src/test/**`, `docs/Architecture.md`, `backend/src/test/java/dev/springrag/LayerStructureTest.java`.

**Interfaces:** `chat → retrieval`, `crawl → knowledge`; 컨트롤러 URL·DTO JSON 이름은 그대로 둔다.

- [x] 새 패키지 이름과 금지 의존을 검증하는 구조 테스트를 작성하고 실패를 본다.
- [x] Controller·DTO·Service·DAO를 서비스별 패키지로 옮기고 import를 수정한다.
- [x] 단위·HTTP·DB·스트림 테스트를 통과시키고 구조 테스트를 녹색으로 만든다.
- [x] 실제 패키지와 의존 방향을 아키텍처 문서에 기록한다.

### Task 3: 저장소와 외부 호출 포트·어댑터

**Files:** `backend/src/main/java/dev/springrag/{chat,knowledge,retrieval}/port/**`, 각 `service/`, `dao/`, `infrastructure/**`, 관련 단위·통합 테스트, `docs/Architecture.md`.

**Interfaces:** 서비스가 사용할 저장·AI 호출 인터페이스를 소유하며 JDBC DAO, VectorStore, Ollama·OCR·리랭커 구현이 이를 충족한다. 공개 API와 결과는 그대로 둔다.

- [x] 서비스가 구현 클래스와 직접 결합되지 않았음을 검증할 실패 구조 테스트를 작성한다.
- [x] 기존 인터페이스를 재사용하고 필요한 포트만 추가해 서비스 의존을 전환한다.
- [x] 권한 필터·데이터 저장·외부 장애 테스트와 전체 Maven 테스트를 통과시킨다.
- [x] 포트와 어댑터 책임을 아키텍처 문서에 기록한다.

### Task 4: 최종 회귀와 문서 정리

**Files:** `docs/adr/0003-modular-monolith-with-adapters.md`, `docs/Architecture.md`, `docs/ApiContract.md`(계약 변경이 있을 때만), `runbooks/SpringRagLocalV0_1_0.md`, `README.md`.

- [x] Maven 전체·Testcontainers·프런트엔드·Python 테스트와 `git diff --check`를 실행한다.
- [x] 변경된 실행 명령과 업로드 경로, API·OpenAPI 계약을 검토한다.
- [x] ADR의 실제 구현·평가·코드 리뷰 결과를 기록하고 관련 문서를 동기화한다.
- [x] 비밀값과 무관한 파일만 변경되었는지 확인한다.
