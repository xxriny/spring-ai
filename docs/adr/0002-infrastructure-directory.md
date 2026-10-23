# ADR 0002: 로컬 인프라 파일을 최상위 infrastructure에 둔다

- 저자: Codex
- 날짜: 2026-10-23

## 상태

적용.

## 컨텍스트

### 문제 정의

#### 배경

DB Compose 파일과 OCR·리랭커 Python 서비스가 저장소 루트에 흩어져 있었다. 사용자는 인프라 관련 작업을 별도 폴더에서 관리하기로 결정했다.

#### 조건

기존 Compose 프로젝트명은 `springai`, DB 볼륨명은 `springai_pgdata`다. 버전별 운영 문서는 루트 `runbooks/`에 둔다. Java 외부 연동 어댑터는 `dev.springrag.infrastructure` 패키지에 있다.

#### 목표

로컬 인프라의 위치를 명확히 하면서 기존 DB 컨테이너와 볼륨을 유지한다.

#### 목표 아닌 것

DB 재생성, 데이터 이전, Java 패키지 구조 변경은 이 결정의 범위가 아니다.

#### 평가

이동한 Compose 설정의 프로젝트명과 볼륨 선언, 기존 컨테이너·볼륨 연결, 이동한 Python 테스트 실행을 확인한다.

## 결정

### 해결 방안

#### 설계

최상위 `infrastructure/`에 DB Compose와 OCR·리랭커 서비스 및 해당 테스트·fixture를 둔다. Compose 프로젝트명 `springai`를 명시한다. Java 어댑터와 루트 런북의 위치는 유지한다.

#### 구현

파일을 `infrastructure/compose.yaml`, `infrastructure/ocr/`, `infrastructure/reranker/`로 이동하고 README·런북·테스트 명령을 갱신했다. 저장소 루트에서 `docker compose -f infrastructure/compose.yaml ...`를 실행한다.

## 결과

### 코드 리뷰

독립 코드 리뷰는 미실시다. 이동 후 Compose가 기존 `springai-db-1`을 인식하고 컨테이너가 `springai_pgdata`를 마운트한 것을 확인했다. OCR 테스트 9개와 리랭커 테스트 3개가 통과했다.

### 타임라인

#### 로드맵

개발용 DB 비밀번호 기본값은 별도 작업에서 제거한다.

#### 대안

- 루트 파일을 남기고 새 폴더에도 복사: 실행 진입점이 둘이 되어 설정이 갈라질 수 있다.
- Compose 기본 프로젝트명 사용: 파일 이동 뒤 기존 볼륨 대신 다른 볼륨을 참조할 수 있다.

#### 참조

- [로컬 인프라 안내](../../infrastructure/README.md)
- [버전별 운영 런북](../../runbooks/SpringRagLocalV0_1_0.md)
