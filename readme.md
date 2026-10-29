# Spring AI RAG 챗봇

## 프로젝트 개요

공용 문서 또는 본인 문서를 선택해 질문하는 RAG 챗봇입니다. 파일과 관리자 URL 수집으로 문서를 등록하고, pgvector·BM25 검색과 Qwen 리랭킹으로 찾은 근거를 바탕으로 로컬에서는 Ollama가 답변합니다. GCP용 모델 프로필은 Vertex AI를 사용하도록 준비했지만 배포와 유료 호출은 아직 검증하지 않았습니다. React 화면에는 답변 조각과 출처가 순차 표시됩니다. 현재 기능과 제한은 [구현 범위](docs/Scope.md)에 정리했습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| 백엔드 | Java 17, Spring Boot 4.0.0, Spring AI 2.0.1, Spring MVC·Reactor `Flux` 채팅 스트림, Spring Security, Spring JDBC, Springdoc OpenAPI, Maven |
| 프런트엔드 | React 19, TypeScript 5, Vite 7 |
| 데이터 | PostgreSQL 17(ParadeDB), pgvector, pg_search(BM25), Spring AI JDBC Chat Memory |
| 로컬 AI | Ollama `qwen3-embedding:8b`·`qwen3.5:9b`, Qwen3-Reranker-0.6B, pdfplumber·PaddleOCR |
| GCP AI 설정 | Vertex AI `gemini-embedding-001`(1536차원)·`gemini-3.5-flash-lite`(정적 설정, 실호출 미검증) |
| 실행·검증 | Docker Compose, Caddy 2, Python, JUnit, Testcontainers |

웹 서버는 Spring MVC이며, Reactor `Flux`로 답변을 스트리밍합니다. 검색과 JDBC는 동기식입니다.

## 로컬 실행

저장소 루트에서 시작합니다. Java 17+, Maven, Node 20+, Python, Docker, Ollama가 필요합니다. OpenAI API 키는 필요하지 않습니다. 모델 다운로드와 Python 의존성 설치는 첫 실행에 시간이 걸릴 수 있습니다.

1. DB를 시작하고 Ollama 모델을 준비합니다.

   ```bash
   docker compose -f infrastructure/compose.yaml up -d db
   ollama pull qwen3-embedding:8b
   ollama pull qwen3.5:9b
   ```

   Ollama가 자동 실행되지 않는 환경에서는 별도 터미널에서 `ollama serve`를 실행합니다.

2. 별도 터미널에서 리랭커를 시작합니다.

   ```bash
   python3 -m venv .venv
   source .venv/bin/activate
   python -m pip install -r infrastructure/reranker/requirements.txt
   python infrastructure/reranker/server.py
   ```

3. PDF를 등록할 경우 별도 터미널에서 추출·OCR 서버를 시작합니다.

   ```bash
   python3 -m venv .venv-ocr
   source .venv-ocr/bin/activate
   python -m pip install -r infrastructure/ocr/requirements.txt
   python infrastructure/ocr/server.py
   ```

4. Git에서 제외된 `.env`에 `APP_ADMIN_PASSWORD`와 `APP_USER_PASSWORD`를 설정하고 백엔드를 시작합니다. 실제 비밀번호는 저장소나 명령 인수에 쓰지 않습니다.

   ```bash
   chmod 600 .env
   set -a
   source .env
   set +a
   mvn -pl backend spring-boot:run
   ```

5. 별도 터미널에서 화면을 시작합니다.

   ```bash
   npm --prefix frontend install
   npm --prefix frontend run dev
   ```

화면은 `http://localhost:5173`, 백엔드 상태는 `http://localhost:8080/api/health`에서 확인합니다. API 문서는 HTTP Basic 인증 후 `http://localhost:8080/swagger-ui.html`에서 봅니다. 기존 DB 재사용·재색인·백업·장애 대응은 [운영 런북](runbooks/SpringRagLocalV0_1_0.md)을 따르세요.

## GCP 모델 프로필

배포에서는 `SPRING_PROFILES_ACTIVE=gcp`와 `GOOGLE_CLOUD_PROJECT`를 설정합니다. VM에 연결된 전용 서비스 계정의 Application Default Credentials를 사용하며 API 키나 JSON 키 파일을 설정하지 않습니다. Vertex 모델 엔드포인트는 VM의 서울 리전과 별개로 `global`입니다. 배포 전 DB는 새로 만들고 GCP 모델로 다시 색인합니다. VM용 Compose는 DB·Python 서비스·백엔드·Caddy 웹을 실행하며 웹의 80·443만 게시합니다. 공개 체험은 방문자별 만료 세션과 DB 일일 한도를 사용하고, GCP Basic 로그인은 관리자만 허용합니다. 실제 VM 배포와 유료 Vertex 호출은 아직 검증하지 않았습니다. 절차는 [GCP 런북](runbooks/SpringRagGcpV0_1_0.md)을 따르세요.

## 문서 안내

| 문서 | 내용 |
| --- | --- |
| [현재 범위](docs/Scope.md) | 지원 기능, 입력 형식, 첫 버전 제한 |
| [API 계약](docs/ApiContract.md) | 인증·권한, 엔드포인트, 요청·응답·오류 |
| [공개 체험 계약](docs/PublicDemo.md) | 방문자 세션, 공용 검색·대화, 일일 한도, 데이터 범위 |
| [아키텍처](docs/Architecture.md) | 컴포넌트, 데이터 흐름, 저장 모델 |
| [하이브리드 검색](docs/HybridRetrieval.md) | 검색 단계, 점수, 벡터 메타데이터, 평가 기준 |
| [답변 품질 평가](docs/AnswerQualityEvaluation.md) | Spring AI 평가기 실행과 결과 해석 |
| [로컬 운영 런북](runbooks/SpringRagLocalV0_1_0.md) | 설치·실행·점검·백업·복구 |
| [GCP 배포 런북](runbooks/SpringRagGcpV0_1_0.md) | Vertex AI 프로필·별도 색인·VM Compose·검증 경계 |
| [인프라 안내](infrastructure/README.md) | DB·OCR·리랭커 실행 파일 |

PR 검증과 `main`의 SHA 태그 이미지 게시는 [.github/workflows/ci.yml](.github/workflows/ci.yml)에 정의했다. 최초 빌드 검증은 [GitHub Actions 실행 기록](https://github.com/xxriny/spring-ai/actions/runs/40650609705)에서 확인했다. Registry 게시와 GCP 배포 결과는 아직 기록되지 않았다.
