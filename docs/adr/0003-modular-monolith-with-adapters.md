# ADR 0003: 어댑터를 둔 서비스별 모듈형 모놀리스

- 저자: Codex
- 날짜: 2026-10-27

## 상태

적용. 백엔드 빌드 모듈, 서비스별 패키지, 저장소·외부 호출 포트와 벡터 어댑터를 전환했다.

## 컨텍스트

### 문제 정의

#### 배경

전환 전 백엔드는 루트 Maven 모듈의 `controller`, `dto`, `service`, `dao`, `infrastructure` 기술 계층별 패키지다. 문서 등록, URL 수집, 하이브리드 검색, 대화 생성이 서로 다른 업무 흐름인데 패키지 경계로 드러나지 않는다. OCR·PDF 추출·리랭커는 인터페이스와 HTTP 구현을 분리했지만, 벡터 저장과 일부 JDBC 접근은 서비스가 구현 기술에 직접 의존한다.

#### 조건

- `frontend/`와 `backend/`를 분리하고, 백엔드는 `chat`, `knowledge`, `retrieval`, `crawl` 서비스별 패키지로 묶는다.
- 각 서비스의 Controller·DTO·Service·DAO 책임과 Spring MVC HTTP 계약, 인증·권한, 스트리밍 응답, DB 스키마·기존 데이터, 색인·검색 결과는 유지한다.
- DB Compose·Python OCR·리랭커 파일은 최상위 `infrastructure/`에 둔다. Java 외부 연동 구현은 `dev.springrag.infrastructure` 아래에 둔다.
- 이동 중에도 저장소 루트에서 실행할 때 `UPLOAD_DIR=./data/uploads`가 기존 원본 파일을 가리키도록 한다.

#### 목표

하나의 Spring Boot 배포 단위 안에서 업무 모듈의 공개 진입점과 의존 방향을 명확히 하고, DB·벡터 저장소·외부 AI 서비스 호출을 교체 가능한 어댑터 경계에 둔다.

#### 목표 아닌 것

마이크로서비스 분리, DB별 서비스 분리, 새 도메인 엔터티 체계 도입, 검색 알고리즘·API 응답 변경, 운영 데이터 이전은 이번 전환의 목표가 아니다.

#### 평가

기존 Maven·프런트엔드·Python 테스트와 DB 통합 테스트를 실행한다. API·OpenAPI 계약, 권한 격리, 업로드·재색인·삭제, 하이브리드 검색, 채팅 스트림을 회귀 확인한다. 패키지 의존 검사에서 `chat → retrieval`, `crawl → knowledge`만 허용하고 서비스가 외부 HTTP·JDBC 구현 클래스를 직접 참조하지 않음을 확인한다. 빌드·기동 작업 디렉터리 변경으로 업로드 원본 경로가 바뀌지 않는지도 확인한다.

## 결정

### 해결 방안

#### 설계

**선택: 하나의 Spring Boot 애플리케이션에 서비스별 패키지와 포트·어댑터 경계를 둔다.** 각 서비스는 `controller/`, `dto/`, `service/`, `dao/` 역할을 유지한다. 서비스 간 호출은 상대 모듈의 공개 서비스 계약으로만 한다. `common/`에는 인증, traceId, 공통 오류처럼 실제로 공유하는 코드만 둔다.

```text
frontend/
backend/
  pom.xml
  src/main/java/dev/springrag/
    chat/{controller,dto,service,dao,port}
    knowledge/{controller,dto,service,dao,port}
    retrieval/{controller,dto,service,dao,port}
    crawl/{controller,dto,service}
    common/{config,controller,dto,port}
    infrastructure/  # Java 외부 서비스·벡터 어댑터
infrastructure/  # DB Compose와 로컬 Python 서비스
```

`port`는 해당 서비스가 필요로 하는 저장·외부 호출 계약이다. JDBC DAO와 Spring AI `VectorStore` 연결, Ollama·OCR·PDF 추출·리랭커 호출은 그 계약의 어댑터다. 기존 DAO 이름과 인터페이스는 가능한 한 재사용한다. 같은 `vector_store`의 쓰기·검색은 각 서비스가 필요한 계약으로 노출하되 저장 구현은 공유할 수 있다. HTTP 요청·응답 DTO는 모듈의 `dto/`에 둔다. 별도 도메인 계층은 실제 불변식이 필요해질 때 도입한다.

모듈 의존 방향은 `crawl → knowledge`, `chat → retrieval`이다. `knowledge`와 `retrieval`은 서로를 호출하지 않고 저장 어댑터를 통해 같은 DB를 사용한다. `common`은 업무 모듈에 의존하지 않는다. 예외 매핑이 필요한 공통 HTTP 코드에는 공개 예외 타입만 노출한다. Spring Modulith 의존성은 우선 추가하지 않고 기존 테스트로 경계를 검사한다.

#### 구현

백엔드 Maven 프로젝트를 `backend/`로 옮기고 루트 집계 POM을 추가했다. 기존 Controller·DTO·Service·DAO를 서비스별 패키지로 옮겼다. `ChatRepository`, `KnowledgeRepository`, `RetrievalDao`, `OcrClient`, `PdfExtractionClient`, `Reranker`를 각 업무 포트로 두고 JDBC DAO·외부 HTTP 구현이 계약을 이행한다. 문서 색인과 검색이 함께 쓰는 `VectorIndex`는 `common.port`에 두고 `SpringAiVectorIndex`가 Spring AI `VectorStore`에 연결한다. Spring Boot Maven 플러그인의 작업 디렉터리를 저장소 루트로 고정해 기본 `./data/uploads`가 기존 원본 위치를 유지한다. README·런북의 실행 명령을 갱신했다. HTTP 계약과 DB 스키마는 변경하지 않았다.

## 결과

### 코드 리뷰

독립 코드 리뷰에서 기본 업로드 경로의 회귀 가능성을 찾아 Spring Boot Maven 플러그인의 작업 디렉터리를 루트로 고정했다. `UPLOAD_DIR`를 제거한 임시 main 실행에서 실제 작업 디렉터리가 저장소 루트임을 확인했다. 기존 HTTP·권한·청크 교체·검색·스트림 테스트를 유지하고, 빌드 위치·업무 패키지·포트 의존을 검증하는 구조 테스트를 추가했다. 전체 Maven 단위 테스트, Testcontainers의 pgvector·BM25 통합 테스트, 프런트엔드 빌드, Python OCR 9건·리랭커 3건이 통과했다. 실제 사용자 데이터에 대한 기동·업로드 확인은 미실시했다. 기존 DB를 변경하지 않기 위해 Testcontainers에서만 통합 검증했다.

### 타임라인

#### 로드맵

1. 완료: 빌드 모듈·실행 경로를 이동하고 Maven 회귀 테스트를 통과시켰다.
2. 완료: 서비스별 패키지를 옮기고 구조 테스트를 추가했다.
3. 완료: 외부 연동·영속성·벡터 접근을 포트·어댑터 경계로 정리하고 통합 테스트를 통과시켰다.

#### 대안

- 기술 계층별 패키지 유지: 이동 비용은 작지만 업무 경계와 모듈 의존이 계속 드러나지 않는다.
- 전체 헥사고날·DDD 재작성: 경계를 강하게 만들 수 있지만 현재 API·색인 동작을 보존하며 구조를 옮기는 목표에 비해 변경 범위가 크다.
- 서비스별 프로세스 분리: 독립 배포 이점은 있으나 현재 단일 DB와 로컬 AI 서비스 운영 규모에는 네트워크·배포 복잡성이 앞선다.

#### 참조

- [현행 아키텍처](../Architecture.md)
- [API 계약](../ApiContract.md)
- [하이브리드 검색 명세](../HybridRetrieval.md)
- [로컬 실행 런북](../../runbooks/SpringRagLocalV0_1_0.md)
- [Spring Modulith 모듈 구성](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
