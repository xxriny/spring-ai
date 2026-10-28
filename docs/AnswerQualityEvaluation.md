# Spring AI 답변 품질 평가

공식 Spring AI 문서 4쪽과 `evaluation/questions.json`의 고정 질문 16개를 사용한다. 검색 품질은 기존 Hit@k·MRR로 평가하고, 생성 답변은 Spring AI 2.0.1의 `RelevancyEvaluator`(질문·근거·답변의 관련성)와 `FactCheckingEvaluator`(근거에 대한 사실성)로 평가한다. 답변의 `[1]` 형식 인용 번호와 출처 문서 ID는 별도 구조 검사다.

## 실행

1. [로컬 실행](../README.md)의 DB, Ollama, 리랭커, 백엔드를 준비한다. **평가 전용 DB를 연결한 백엔드**를 사용한다. 수집은 해당 DB의 공용 문서를 갱신하고 답변 수집은 관리자 대화 16개를 저장한다. 기존 사용자 데이터가 든 DB에 실행하지 않는다.
2. 비밀값은 Git 제외 `.env` 등에서 주입한다. `EVAL_ADMIN_PASSWORD`는 실행 중인 백엔드의 `APP_ADMIN_PASSWORD`와 같아야 한다. 다음 명령은 문서 수집·검색 평가 뒤 답변을 수집한다.

   ```bash
   python3 evaluation/evaluate.py --execute --answers --base-url http://127.0.0.1:8080
   ```

3. 출력된 `evaluation/results/<UTC 시각>/answer.jsonl`을 Spring AI 평가기에 입력한다. 평가 모델은 기본적으로 로컬 Ollama `qwen3.5:9b`이며 `OLLAMA_CHAT_MODEL`, `OLLAMA_BASE_URL`로 변경할 수 있다.

   ```bash
   mvn -pl backend -Dtest=AnswerQualityEvaluationIT \
     -Devaluation.input="$PWD/evaluation/results/<UTC 시각>/answer.jsonl" test
   ```

실행하면 문항별 판정과 집계를 담은 [`docs/evaluations/`](evaluations/README.md)의 `SpringAiAnswerQuality-<UTC 시각>.md`가 생성된다. **이 Markdown 보고서를 검토한 뒤 Git에 포함한다.** 같은 실행의 `answer.jsonl`, `answer-evaluation.jsonl`, `answer-evaluation-summary.json`은 재현과 진단을 위한 원시 결과로 `evaluation/results/`에 남기며 Git에서 제외한다. 보고서에는 답변·passage 원문이나 비밀값을 싣지 않는다. 평가가 중간에 실패하면 명령이 실패하고 새 보고서는 생성되지 않는다.

## 중단 후 재개

- `answer.jsonl`이 16줄이면 수집·검색·답변을 다시 실행하지 않는다. 위의 Maven 평가 명령만 실행한다. 평가기는 `answer-evaluation.jsonl`에 문항별 판정을 즉시 기록하며, 같은 명령을 다시 실행하면 완료된 문항을 건너뛴다. 재개 시 같은 평가 모델을 사용해야 한다.
- 답변이 16줄보다 적으면 **같은 평가 DB**를 연결한 백엔드를 다시 시작하고 `.env`의 `APP_ADMIN_PASSWORD`를 `EVAL_ADMIN_PASSWORD`로 주입한 뒤 아래 명령을 실행한다. 이미 완료한 문항은 건너뛰며, `--execute`를 다시 사용하면 문서를 재수집하므로 사용하지 않는다.

  ```bash
  python3 evaluation/evaluate.py \
    --resume-dir evaluation/results/<UTC 시각> --base-url http://127.0.0.1:8081
  ```

- 결과 파일의 문항 ID 순서가 고정 질문과 다르면 재개를 중단한다. 중간 파일을 임의로 합치지 말고 원인을 확인한다.

## 판정 기준과 한계

- `judged`만 평가기의 분모에 넣는다. `no_context`, `search_error`, `generation_error`는 별도 건수로 보고한다. `relevant`, `factual`, `citationsStructurallyValid`는 각 조건을 통과한 **건수**다. 비율이 필요하면 `judged`로 나눈다.
- 두 평가기는 모델이 내린 이진 판정이므로 사람의 정답 판정과 같지 않다. 생성과 평가에 같은 로컬 모델을 사용하므로 자기평가 편향이 있을 수 있다. 인용 구조 검사는 번호 범위와 출처 문서 ID 순서만 확인하며, 실제 문장이 그 인용에 의해 뒷받침되는지는 사실성 판정과 수동 검토가 필요하다.
- `answer.jsonl`의 passage는 답변 호출 직전에 독립 `GET /api/search`로 저장한 값이다. 첫 질문에는 대화 기반 쿼리 리라이팅이 없어 같은 질문을 사용하지만, 색인·설정이 검색과 답변 사이에 바뀌면 실제 생성 context와 어긋날 수 있다. 평가 중에는 색인과 설정을 고정한다.
- 모델 호출은 최대 16개 생성 답변과 32개 평가 판정이며 전부 로컬 Ollama에서 수행한다. CPU 추론 시간은 환경에 따라 길어질 수 있다. 현재 설정의 실측 결과는 실행 후 별도 평가 기록으로 남긴다.
