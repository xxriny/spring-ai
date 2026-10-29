# Spring RAG GCP 모델 설정 런북 (v0.1.0)

이 문서는 백엔드의 `gcp` 프로필, 색인 분리, VM Compose 배포 절차를 기록한다. Vertex AI 실호출과 유료 품질 평가는 아직 수행하지 않았다.

공개 방문자 세션·한도·API 계약은 [공개 체험 명세](../docs/PublicDemo.md)에 기록한다. 현재 VM에 이미지를 배포하거나 실제 두 브라우저로 권한을 검증하지는 않았다.

## 모델과 인증

| 용도 | 로컬 기본 프로필 | GCP 프로필 |
| --- | --- | --- |
| 채팅·후속 질문 재작성 | Ollama `qwen3.5:9b` | Vertex AI `gemini-3.5-flash-lite` |
| 문서·질문 임베딩 | Ollama `qwen3-embedding:8b`, 1536차원 | Vertex AI `gemini-embedding-001`, 출력 1536차원 |
| 색인 식별자 | `qwen3-embedding:8b/1536` | `gemini-embedding-001/1536` |

기존 계획의 `gemini-2.5-flash-lite`는 [Google Cloud 모델 수명 주기](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/model-versions)에 따르면 2026-10-20 지원 종료다. 그래서 배포 기본 채팅 모델을 `gemini-3.5-flash-lite`로 바꿨다. 모델과 임베딩의 실제 프로젝트 접근성, 호출 비용, 답변 품질은 배포 전에 별도로 확인해야 한다.

VM에는 전용 서비스 계정을 연결하고 Vertex AI 사용자 권한을 부여한다. `SPRING_PROFILES_ACTIVE=gcp`, `GOOGLE_CLOUD_PROJECT`, `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, 앱 로그인 비밀번호와 서비스 URL을 런타임 환경에서 공급한다. `GOOGLE_APPLICATION_CREDENTIALS`, `spring.ai.google.genai.api-key`, `credentials-uri`는 설정하지 않는다. Spring AI Google GenAI 연동이 VM 메타데이터의 Application Default Credentials를 사용한다. 모델 엔드포인트 `global`은 서울 VM 리전과 별개다. [Spring AI Google GenAI 채팅](https://docs.spring.io/spring-ai/reference/api/chat/google-genai-chat.html), [텍스트 임베딩](https://docs.spring.io/spring-ai/reference/api/embeddings/google-genai-embeddings-text.html).

`gemini-embedding-001`은 기본 3072차원이지만 출력 차원을 1536으로 요청한다. 모델 이름과 차원이 같아도 Ollama 벡터와 값은 호환되지 않는다. Spring AI 2.0.1의 Google GenAI 임베딩 구현은 `taskType` 옵션을 실제 SDK 요청에 넣지 않으므로 문서용 `RETRIEVAL_DOCUMENT`와 질문용 `RETRIEVAL_QUERY` 분리는 이 버전에서 보장되지 않는다. 검색 품질은 실측 전까지 미검증이다.

[Google Cloud 텍스트 임베딩 API](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/models/text-embeddings-api)는 `gemini-embedding-001` 온라인 요청당 텍스트 하나만 허용한다. 그래서 GCP 프로필의 PgVectorStore 문서 배치를 1개로 제한한다. 많은 문서를 색인하면 호출 수와 시간이 청크 수만큼 늘 수 있다.

## 신규 DB와 재색인

1. 로컬 DB와 별도의 빈 GCP ParadeDB를 만든다. `pgvector`와 `pg_search`를 사용할 수 있어야 한다. 로컬 DB 덤프의 `vector_store`, `knowledge_document`, `knowledge_chunk`를 GCP DB에 복원하거나 복사하지 않는다.
2. GCP 앱을 인증된 관리 환경에서 시작해 DB 스키마를 초기화한다. 시작 검사는 `vector_store.metadata.embeddingModel`이 `gemini-embedding-001/1536`과 다른 행을 발견하면 실패한다.
3. 공식 문서 원본 URL부터 관리자 권한으로 다시 수집한다. 업로드 문서는 원본 파일이 있을 때만 재등록한다. 각 호출은 Vertex AI 사용료가 발생할 수 있으므로 수집 건수와 예상 비용을 먼저 정한다.
4. `knowledge_document`, `knowledge_chunk`, `vector_store` 건수와 `embeddingModel` 값, 샘플 검색 결과를 확인한다. 로컬 평가와 GCP 평가 결과를 별도 경로에 저장한다.

모델 또는 차원을 바꿀 때는 DB 덤프와 업로드 원본을 한 백업 세트로 만들고 복원 검증 후 **새 DB**에 재색인한다. 단순히 `embeddingModel` 메타데이터만 새 값으로 바꾸면 안 된다. 모델 ID가 같아도 앞으로 task type 등 임베딩 요청 방식이 달라지면 역시 새 DB에 재색인해야 한다.

## VM 컨테이너 준비

`infrastructure/deploy/compose.yaml`은 호스트의 `/mnt/spring-rag-data`를 DB·업로드·모델 캐시·Caddy 인증서 저장소로 사용한다. 네트워크에는 웹 컨테이너의 80·443만 게시한다. 운영 DB는 로컬 Compose의 `springai_pgdata`와 별개다.

1. VM에서 데이터 디스크가 `/mnt/spring-rag-data`에 마운트됐는지 `findmnt /mnt/spring-rag-data`로 확인한다. 마운트 실패 상태에서 Compose를 시작하면 데이터가 부팅 디스크에 생길 수 있으므로 중단한다.
2. `sudo install -d -m 700 /mnt/spring-rag-data/postgres /mnt/spring-rag-data/reranker-models /mnt/spring-rag-data/ocr-models /mnt/spring-rag-data/caddy-data /mnt/spring-rag-data/caddy-config`로 보존 디렉터리를 만들고, 업로드 디렉터리는 `sudo install -d -o 10001 -g 10001 -m 700 /mnt/spring-rag-data/uploads`로 만든다. 백엔드 컨테이너는 UID 10001로 실행한다.
3. 저장소의 `infrastructure/deploy/.env.example`을 VM에서 `infrastructure/deploy/.env`로 복사해 도메인, 프로젝트, 고정된 이미지 태그와 긴 무작위 DB·관리자 비밀번호를 채운다. `chmod 600 infrastructure/deploy/.env`를 적용하고 Git에 넣지 않는다. 예시의 `COMMIT_SHA`와 `replace-with-long-random-value`를 그대로 쓰지 않는다.
4. 저장소 루트에서 `docker compose --env-file infrastructure/deploy/.env -f infrastructure/deploy/compose.yaml config -q`로 구성을 검증한다. GitHub Actions가 게시한 같은 커밋 SHA의 이미지를 `pull`한 뒤 `up -d`, `ps`를 실행한다. 구매한 도메인이 없다면 `APP_DOMAIN=<고정 IPv4>.sslip.io`를 사용할 수 있다. 이 무료 DNS 서비스는 외부 의존성이므로 공개 시연 전에 이름 확인과 인증서 발급을 검증한다. 두 경우 모두 80·443 방화벽이 허용되어야 Caddy가 인증서를 발급한다.
5. `curl -fsS http://localhost/api/health`는 HTTPS가 활성화되면 리다이렉트될 수 있으므로 `curl -fsS https://<APP_DOMAIN>/api/health`로 헬스 체크한다. 공개 방문자 세션·서로 다른 브라우저의 대화 분리·업로드와 삭제 거부를 확인한 뒤 안내 페이지에 시연 가능 상태를 표시한다.

프런트엔드 이미지 빌드 시 `--build-arg VITE_PUBLIC_DEMO=true`를 전달해야 공개 체험 진입 버튼을 표시한다. 로컬 프런트엔드는 기본값 `false`로 기존 로그인을 유지한다. 앱 컨테이너는 VM 서비스 계정의 메타데이터 인증을 사용하며 JSON 키 파일을 마운트하지 않는다. Artifact Registry 읽기 권한, Vertex AI 사용자 권한 및 API 사용 가능 여부는 실제 배포 전에 확인한다.

Mac에서 직접 이미지를 빌드하면 VM의 `linux/amd64`와 CPU 아키텍처가 다를 수 있다. 빌드·푸시에는 `docker buildx build --platform linux/amd64 --push`를 사용하고 이미지 태그에는 **빌드한 코드의 커밋 SHA**를 넣는다. 현재 작업 트리가 커밋되지 않았으면 `git rev-parse HEAD`의 SHA는 이미지 내용과 다르므로 먼저 작업 내용을 검토·커밋해야 한다.

`.github/workflows/ci.yml`은 PR과 `main`에서 Java 단위·DB 통합 테스트, Python 테스트, React 두 모드 빌드, Compose 형식 및 4개 컨테이너 빌드를 검증한다. `main` 푸시에서 검증이 끝나면 GitHub Actions 저장소 변수 `GCP_WIF_PROVIDER`(제공자 전체 이름)와 `GCP_IMAGE_PUBLISHER`(서비스 계정 이메일)를 사용해 Workload Identity Federation으로 인증하고, 4개 이미지를 Artifact Registry의 `spring-rag` 저장소에 커밋 SHA 태그로 게시한다. PR에는 게시 권한을 사용하지 않는다. GitHub Actions의 최초 빌드 검증은 [실행 기록](https://github.com/xxriny/spring-ai/actions/runs/40650609705)으로 확인했지만, 이미지 게시·VM 배포·수동 승인·헬스 체크 실패 시 자동 롤백은 아직 실증되지 않았다.

## 현재 검증 경계

`ModelProfileConfigurationTest`는 로컬과 GCP 프로필의 모델·차원·키 파일 미설정을, `KnowledgeServiceTest`는 청크 메타데이터를, `JdbcRetrievalDaoIT`는 로컬 벡터가 GCP 시작 검사에서 거절되는 것을 검증한다. 이 테스트는 Vertex AI를 호출하지 않으므로 서비스 계정 권한, 글로벌 엔드포인트 응답, 실제 1536차원 반환, 과금·품질은 확인하지 않는다. 운영 환경에 적용하기 전 [Google Cloud 모델 위치](https://docs.cloud.google.com/gemini-enterprise-agent-platform/resources/locations)와 가격을 다시 확인한다.
