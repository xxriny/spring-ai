# 로컬 인프라

- `compose.yaml`: PostgreSQL 17/ParadeDB. 프로젝트명 `springai`를 고정해 기존 `springai_pgdata` 볼륨을 계속 사용한다.
- `reranker/`: Qwen3 리랭커 로컬 HTTP 서비스와 테스트.
- `ocr/`: PDF 텍스트·표 추출 및 PaddleOCR 로컬 HTTP 서비스와 테스트.

저장소 루트에서 `docker compose -f infrastructure/compose.yaml up -d db`로 DB를 시작한다. 이동 후 같은 명령의 `ps db`가 기존 `springai-db-1`을 인식하고, 기존 볼륨명이 `springai_pgdata`임을 확인했다. Python 서비스의 설치·실행·복구 절차는 [버전별 런북](../runbooks/SpringRagLocalV0_1_0.md)을 따른다. 백엔드 Java 어댑터는 기존 `src/main/java/dev/springrag/infrastructure/` 패키지에 둔다.
