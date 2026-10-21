# Spring RAG 챗봇 로컬 운영 런북 (v0.1.0)

이 문서는 현재 `admin`·`user` 두 계정을 사용하는 로컬 첫 버전의 기동, 확인, 장애 대응 절차다. 명령은 저장소 루트에서 실행한다. 생성용 채팅 모델은 아직 설정되지 않았으며 대화 질문 API는 503을 반환한다. 검색 API는 별도로 동작한다.

## 구성과 데이터

| 구성 요소 | 기본 위치 | 역할 |
| --- | --- | --- |
| PostgreSQL/pgvector | Docker Compose `db`, 호스트 `localhost:5432` | 문서 목록, 청크 ID, 벡터, 대화·채팅 메모리 |
| Spring Boot | `localhost:8080` | 인증, ETL, 검색·리랭킹 API |
| Qwen 리랭커 | `127.0.0.1:8091` | 검색 후보 1~10개에 대한 로컬 재정렬 |
| React/Vite | `localhost:5173` | 로그인, 문서 등록, 대화 화면 |
| 파일 원본 | `UPLOAD_DIR`, 기본 `./data/uploads` | 업로드한 원본 파일 |
| 평가 원시 결과 | `evaluation/results/<UTC>/` | 수집·검색 응답과 지표; Git에서 제외됨 |

Spring AI `text-embedding-3-small`은 문서 적재뿐 아니라 **검색 질문을 벡터로 바꿀 때도** OpenAI API를 호출한다. Qwen은 로컬 모델이지만 첫 실행 시 모델 파일 다운로드가 필요하다.

## 기동 전 확인

1. Java 17+, Maven, Node.js 20+, Python, Docker를 준비한다. Docker가 실행 중인지 `docker info`로 확인한다.
2. `5432`, `8080`, `8091`, `5173` 포트의 기존 사용 여부를 확인한다. 이미 실행 중인 이 프로젝트 프로세스가 있으면 중복 실행하지 않는다.
3. 관리자·사용자 비밀번호와 OpenAI API 키를 준비한다. 실제 값을 저장소에 커밋하거나 명령줄 인수에 넣지 않는다.
4. 기존 DB를 재사용한다면 `compose.yaml`의 `pgdata` 볼륨과 `UPLOAD_DIR`이 기대한 데이터인지 확인한다. `docker compose down -v`는 볼륨을 삭제하므로 사용하지 않는다.

| 환경 변수 | 기본값 | 설정 목적 |
| --- | --- | --- |
| `APP_ADMIN_PASSWORD` | 없음, 필수 | 로컬 `admin` 로그인 |
| `APP_USER_PASSWORD` | 없음, 필수 | 로컬 `user` 로그인 |
| `OPENAI_API_KEY` | 없음 | 문서·검색 질문 임베딩 |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/springrag` | DB 접속 주소 |
| `DATABASE_USER` / `DATABASE_PASSWORD` | `springrag` / `springrag` | DB 계정 |
| `UPLOAD_DIR` | `./data/uploads` | 업로드 원본 위치 |
| `RERANKER_URL` | `http://127.0.0.1:8091/rerank` | Spring Boot에서 호출할 로컬 리랭커 |

## 시작 순서

### 1. 데이터베이스

```bash
docker compose up -d db
docker compose ps db
docker compose exec -T db pg_isready -U springrag -d springrag
```

`pg_isready`가 연결 가능 상태를 반환한 뒤 다음 단계로 간다. 데이터는 Compose의 `pgdata` 볼륨에 남는다.

### 2. 로컬 리랭커

첫 실행 전에 [모델 카드](https://huggingface.co/Qwen/Qwen3-Reranker-0.6B)의 다운로드 용량과 사용 가능한 메모리·CPU/GPU를 확인한다. 설치와 서버 시작은 별도 터미널에서 실행한다.

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r reranker/requirements.txt
python reranker/server.py
```

모델 로드가 끝나면 서버가 `127.0.0.1:8091`에 바인딩된다. 다른 터미널의 `nc -z 127.0.0.1 8091`은 포트 접속만 확인한다. 실제 추론 품질이나 점수는 이 확인만으로 검증되지 않는다. 모델 파일은 저장소에 넣지 않는다.

### 3. Spring Boot와 화면

백엔드 터미널에서 환경 변수를 설정하고 시작한다. 예를 들어 Git에서 제외된 `.env`에 `APP_ADMIN_PASSWORD`, `APP_USER_PASSWORD`, `OPENAI_API_KEY`를 각 한 줄씩 `이름=값` 형식으로 저장한다. 파일 권한을 제한하고, 실제 값을 터미널 명령에 직접 입력하지 않는다.

```bash
chmod 600 .env
set -a
source .env
set +a
mvn spring-boot:run
```

화면은 별도 터미널에서 시작한다.

```bash
npm --prefix frontend install
npm --prefix frontend run dev
```

브라우저에서 `http://localhost:5173`을 열고 `admin` 또는 `user`로 로그인한다. 백엔드 시작 시 `schema.sql`, Spring AI의 pgvector·JDBC 메모리 스키마 초기화가 실행된다. 시작 실패 시 아래 장애 대응을 확인한다.

## 상태 확인: 유료 호출 없음

다음 확인은 문서 임베딩이나 검색 질문 임베딩을 호출하지 않는다.

```bash
curl -fsS http://127.0.0.1:8080/api/health
curl -u admin http://127.0.0.1:8080/api/me
curl -u user 'http://127.0.0.1:8080/api/documents?scope=PRIVATE'
docker compose exec -T db psql -U springrag -d springrag -c 'SELECT count(*) FROM knowledge_document;'
```

`curl -u admin`과 `curl -u user`는 비밀번호를 명령 인수에 쓰지 않으면 입력을 요청한다. `/api/health`의 `{"status":"UP"}`는 **HTTP 핸들러만** 확인한다. DB, OpenAI, Qwen 준비 상태를 보증하지 않는다. DB는 `pg_isready`와 읽기 전용 조회로, Qwen은 프로세스 로그와 포트로 따로 확인한다.

## 문서 등록과 검색

아래 요청은 실제 OpenAI 임베딩 호출을 일으킬 수 있다. 실행 전에 대상 파일·페이지 수, 예상 청크/호출 수, 현재 단가와 비용 상한, 중단 조건을 확인하고 승인을 받는다. URL의 `maxPages=1`은 한 페이지만 수집하지만 페이지당 청크 수·임베딩 호출 수를 고정하지는 않는다.

```bash
# 관리자: 공용 파일 등록
curl -u admin -F 'file=@/path/to/document.pdf' -F 'category=Spring AI docs' \
  http://127.0.0.1:8080/api/documents

# 사용자: 내 문서 등록
curl -u user -F 'file=@/path/to/notes.txt' -F 'category=Personal' \
  http://127.0.0.1:8080/api/documents

# 관리자: 지정 URL 한 쪽만 수집
curl -u admin -H 'Content-Type: application/json' \
  -d '{"url":"https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html","category":"Spring AI docs","maxPages":1}' \
  http://127.0.0.1:8080/api/admin/crawl

# 공용 문서 검색. PRIVATE를 지정하면 로그인한 계정의 내 문서만 검색한다.
curl -G -u user --data-urlencode 'query=How does the ETL pipeline work?' \
  --data-urlencode 'scope=SHARED' http://127.0.0.1:8080/api/search
```

수집 응답의 `indexed`, `skipped`, `errors`를 함께 확인한다. `indexed=1`만으로 기대 문서의 근거 청크가 검색된다고 판단하지 않는다. 검색 응답의 `candidates`는 벡터 Top-10·유사도 기준 0.3 결과이고 `reranked`는 Qwen 점수 순이다. Qwen의 점수는 확률이 아니므로 0.3을 다시 적용하지 않는다. `PRIVATE` 범위의 소유자는 요청 파라미터가 아닌 로그인 계정에서 결정된다.

## 검색 평가

```bash
python3 evaluation/evaluate.py
python3 -m unittest discover -s evaluation -p 'test_*.py'
```

기본 명령은 공식 문서 URL 4개와 질문 16개를 검증하고 품질을 **미측정**으로 표시한다. 실제 평가는 승인된 비용 범위 안에서 Qwen과 백엔드를 기동한 뒤 `EVAL_ADMIN_PASSWORD`를 설정하고 `python3 evaluation/evaluate.py --execute`로 실행한다. 이 명령은 URL별 `maxPages=1`로 수집 4회와 검색 질문 16개를 수행한다. 원시 응답과 결과는 `evaluation/results/<UTC>/`의 `config.json`, `ingest.jsonl`, `search.jsonl`, `summary.json`에서 확인한다. 오류가 난 질문은 성공한 지표 분모에 섞이지 않는다. 문서·청크 Hit@1/3/5/10과 MRR은 후보와 리랭킹 각각 계산되며 답변 정확도를 뜻하지 않는다.

## 종료와 백업

화면·백엔드·리랭커는 각 실행 터미널에서 정상 종료한다. DB만 중지할 때는 `docker compose stop db`를 사용한다. `docker compose down`은 컨테이너를 내리지만 명명 볼륨은 유지한다. **`down -v`는 사용하지 않는다.**

백업 중 문서 등록·삭제·재수집을 중단한다. PostgreSQL 덤프와 `UPLOAD_DIR` 원본을 같은 백업 세트로 보관하고, 평가 원시 결과가 필요하면 `evaluation/results/`도 따로 보관한다. 기본 업로드 경로를 사용하는 경우의 예시는 다음과 같다.

```bash
BACKUP_DIR=/path/to/backups
mkdir -p "$BACKUP_DIR"
docker compose exec -T db pg_dump -U springrag -d springrag -Fc > "$BACKUP_DIR/springrag.dump"
tar -C data -czf "$BACKUP_DIR/uploads.tgz" uploads
docker compose exec -T db pg_restore --list < "$BACKUP_DIR/springrag.dump" | head
```

복구는 우선 별도 DB/작업 디렉터리에서 덤프를 복원해 문서·청크·벡터 건수와 샘플 검색을 확인한다. 검증 뒤 앱을 중지하고 운영 DB 및 원본 파일의 **같은 경로**를 복원한다. DB의 `stored_path`는 업로드 원본 경로를 참조하므로 `UPLOAD_DIR`만 다른 곳으로 옮기지 않는다. 기존 DB나 볼륨을 바로 덮어쓰는 복원은 이 런북에서 자동 실행하지 않는다.

## 장애 대응

| 증상 | 확인 | 조치 |
| --- | --- | --- |
| 백엔드 시작 시 DB 연결 실패 | `docker compose ps db`, `pg_isready`, `DATABASE_*` | DB를 시작하고 접속 정보를 맞춘 뒤 백엔드 재시작 |
| 로그인 401 | `/api/me`, 계정명·비밀번호 환경 변수 | `admin`/`user` 계정과 비밀번호 재확인. 비밀번호 변경 시 백엔드 재시작 |
| 관리자 수집 403 | 로그인 계정 | `/api/admin/crawl`은 `admin`만 호출 가능 |
| 등록·검색 400 | 카테고리 1~80자, 지원 파일 형식·20MB 제한, 질문 1~4000자, 범위 | 요청 값을 수정하고 재시도. 실패 URL은 `errors` 확인 |
| 검색 503, 리랭커 오류 | Qwen 프로세스 로그, `8091` 포트, `RERANKER_URL` | 리랭커를 기동/복구한 뒤 같은 질문 재시도. 후보가 없으면 리랭커를 호출하지 않음 |
| 대화 질문 503, 생성 모델 미설정 | `spring.ai.model.chat` | 현재 첫 버전의 예상 동작. 독립 `/api/search`로 검색 경로 확인 |
| 색인·검색 중 OpenAI/DB 오류 | 백엔드 로그, API 키·DB 상태, 호출 한도 | 원인 복구 후 재시도. 변경 URL 재수집 실패 시 기존 카탈로그·청크가 유지되도록 구현됨. 조회로 확인 |
| 수집 `indexed=0` 또는 `errors` 발생 | URL별 HTTP 상태, 경로 범위, 리다이렉트, 파일 형식 | 오류 URL만 재시도. 동일 URL·동일 내용은 중복 색인하지 않음 |

URL 수집은 동기식이며 관리자만 실행할 수 있다. DNS 검사 뒤 접속할 때 주소가 바뀌는 위험이 남아 있으므로, 신뢰하지 않는 관리자에게 URL 수집 권한을 주는 환경에서는 서버 네트워크의 외부 송신 제한을 먼저 적용한다.
