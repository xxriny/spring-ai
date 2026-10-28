# 공개 체험 API와 데이터 (v0.1.0)

GCP 프로필에서만 `/api/public/**`가 제공된다. 방문자는 계정 없이 24시간 세션을 발급받고 공용 문서 검색과 공용 대화를 사용할 수 있다. 로컬 프로필의 `admin`·`user` Basic 로그인은 유지된다. GCP 프로필의 Basic 로그인은 `admin`만 만든다.

## API 계약

| 엔드포인트 | 인증 | 요청 | 성공 응답 | 오류 |
| --- | --- | --- | --- | --- |
| `POST /api/public/sessions` | 없음 | 본문 없음 | `200 {token, expiresAt}`; `Cache-Control: no-store` | `500` 저장 장애 |
| `GET /api/public/conversations` | 방문자 Bearer | 없음 | `200 ConversationDto[]` | `401` 토큰 오류 |
| `POST /api/public/conversations` | 방문자 Bearer | 본문 없음 | `200 ConversationDto`, 범위 `SHARED` | `401` 토큰 오류 |
| `GET /api/public/conversations/{id}/messages` | 방문자 Bearer | 본인 대화 ID | `200 MessageDto[]` | `400` 소유권 오류, `401` 토큰 오류 |
| `POST /api/public/conversations/{id}/messages` | 방문자 Bearer | `{ "text": "질문" }` | `200 AnswerDto` | `400`, `401`, `429`, `503` |
| `POST /api/public/conversations/{id}/messages/stream` | 방문자 Bearer | `{ "text": "질문" }` | `200` NDJSON `sources`·`delta`·`done` | `400`, `401`, `429`, `503` |
| `POST /api/public/search` | 방문자 Bearer | `{ "query": "검색어" }` | `200 SearchResultDto`, 최종 근거는 `passages` | `400`, `401`, `429`, `503` |

Bearer 헤더는 `Authorization: Bearer <token>`이다. 오류 본문은 `{ "error": "설명" }` 형식이다. 질문과 검색어는 공백을 제외한 1~500자다. 검색과 채팅 요청마다 UTC 날짜 기준으로 방문자 세션당 5회, 전체 30회의 사용량을 PostgreSQL 트랜잭션에서 차감한 뒤 Vertex AI 호출을 시작한다. 실패한 요청도 사용량에 포함된다.

`visitor_session`은 UUID, SHA-256 토큰 해시, 만료 시각을 저장한다. `visitor_daily_usage`는 `(usage_day, visitor_id)`별 사용량을, `global_daily_usage`는 UTC 날짜별 전체 사용량을 저장한다. 대화의 `owner_id`는 `visitor:<세션 UUID>`이고 조회·질문 때 소유권을 확인한다. 이 세 테이블은 기존 로컬 계정 데이터와 관계가 없으며 원문 토큰은 DB에 저장하지 않는다.

방문자 토큰은 프런트엔드의 `sessionStorage`에만 보관한다. 새 탭·브라우저는 새 세션을 받으므로 개인별 5회를 새 세션으로 우회할 수 있다. 전체 30회가 일일 비용 보호의 최종 한도다. 한 번의 채팅 요청에는 후속 질문 재작성, 질문 임베딩, 답변 생성 등 Vertex 호출이 여럿 생길 수 있으므로 30회는 모델 호출 횟수의 상한이 아니다. 공개 시연 전에 가격과 예상 호출 수를 확인한다.

기존 `/api/documents`, `/api/admin/crawl`, `/api/conversations`, `/api/search`는 Basic 인증 경로다. GCP 프로필에는 공용 `user` 계정이 없으며 방문자 Bearer 토큰으로 업로드·수집·삭제·개인 문서에 접근할 수 없다. UI에서도 방문자에게 해당 조작을 표시하지 않지만 권한 판단은 서버에서 수행한다.

실제 공개 전 서로 다른 두 브라우저에서 대화 목록이 분리되는지, 방문자 토큰으로 업로드·삭제가 거부되는지, 한도 초과 후 추가 Vertex 호출이 발생하지 않는지 확인한다. 이 검증은 아직 실서비스 환경에서 실시하지 않았다.
