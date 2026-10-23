# PDF 머리말·꼬리말 및 표 추출 Implementation Plan

> 경로 참고: 이 계획 작성 후 Python OCR 서비스와 fixture는 `ocr/`에서 `infrastructure/ocr/`로 이동했다. 아래 당시 경로는 현재 위치로 치환해 읽는다.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 텍스트 PDF에서 반복 머리말·꼬리말을 제거하고 표 행을 Markdown 청크로 분리해 색인한다.

**Architecture:** 로컬 Python 서버가 `pdfplumber`로 페이지 본문과 표 행을 추출한다. Java 서비스는 응답을 DTO로 검증한 후 기존 청크 분할·pgvector 적재 경로에 본문과 표 행을 전달한다. 이미지 페이지만 기존 OCR을 호출한다.

**Tech Stack:** Java 17, Spring Boot 4.0.0, Spring AI 2.0.1, PDFBox, Python 3, pdfplumber 0.11.x, JUnit, Python unittest.

**Spec:** `docs/plan/PdfHeaderFooterAndTableExtraction.md`

## Global Constraints

- 이번 범위는 텍스트 PDF다. Word·HTML 표 및 스캔 PDF 표 구조 인식은 포함하지 않는다. 이미지 PDF의 기존 OCR 텍스트 추출은 유지한다.
- 표 요약용 LLM은 호출하지 않는다. 표는 행별 Markdown으로 색인한다.
- 본문 청크는 최대 800토큰·최대 100토큰 오버랩을 유지한다. 표 행과 페이지 경계에는 오버랩을 만들지 않는다.
- 기존 소유권·범위 필터와 검색 Top-k 10·유사도 기준 0.3·Qwen 재정렬을 바꾸지 않는다.
- PDF 원문, 표 내용, 비밀번호, API 키를 로그에 남기지 않는다. 유료 임베딩 호출은 비용 상한을 정해 별도 승인받은 뒤 실행한다.
- 기존 색인은 자동으로 바뀌지 않는다. 동일 URL 재수집은 같은 바이트면 건너뛴다.
- 기존 작업 폴더의 미커밋 OCR 변경을 보존한다. 커밋·푸시는 별도 요청 전까지 하지 않는다.

## File Structure

- `ocr/pdf_extract.py`: PDF 페이지 본문·반복 가장자리 줄·표 셀의 순수 추출 로직.
- `ocr/server.py`, `ocr/requirements.txt`: 원본 PDF 바이트를 받는 로컬 `/pdf/extract` 경로와 pdfplumber 의존성.
- `ocr/test_pdf_extract.py`, `ocr/test_server.py`, `ocr/fixtures/repeated_table.pdf`: Python 경계·추출 테스트와 비민감 합성 PDF.
- `src/main/java/dev/springrag/dto/PdfPageDto.java`, `PdfTableRowDto.java`: Python 응답 자료형.
- `src/main/java/dev/springrag/service/PdfExtractionClient.java`, `infrastructure/LocalPdfExtractionClient.java`: PDF 추출 호출 경계와 응답 검증.
- `src/main/java/dev/springrag/service/KnowledgeService.java`: 추출된 본문·표 행을 `Document`로 만들고 기존 ETL에 연결.
- `src/test/java/dev/springrag/PdfExtractionClientTest.java`, `KnowledgeExtractionTest.java`: HTTP 응답 검증과 색인 결과 테스트.
- `src/main/resources/application.yml`, `AGENTS.md`, `readme.md`, `runbooks/SpringRagLocalV0_1_0.md`: 로컬 주소·기술 스택·메타데이터·운영 절차.

## Review Focus

1. 한 쪽짜리 PDF에는 반복 머리말 판정이 없어 제목이 남아야 한다. Task 1 테스트에 포함한다.
2. 여러 쪽의 반복 제목과 고유 하단 주석이 함께 있으면 반복 제목만 제거돼야 한다. Task 1 테스트에 포함한다.
3. 병합 셀의 직무명이 여러 행에 걸친 표는 각 행에 해당 직무명이 남아야 한다. Task 2 테스트에 포함한다.
4. 셀 안의 `|`와 줄바꿈은 Markdown 열·행을 깨지 않아야 한다. Task 2 테스트에 포함한다.
5. 추출 서비스의 비정상 응답·중단은 기존 URL 카탈로그와 벡터를 교체하지 않아야 한다. Task 3·4 테스트에 포함한다.

---

### Task 1: 반복 가장자리 줄 제거와 본문 추출

**Files:** Create `ocr/pdf_extract.py`, `ocr/test_pdf_extract.py`, `ocr/fixtures/repeated_table.pdf`; modify `ocr/requirements.txt`.

**Interfaces:** Produce `extract_pdf(data: bytes) -> dict` with `{"pages": [{"pageNumber": int, "body": str, "ocrRequired": bool, "tables": list}]}`. Each page's `body` excludes table rectangles and repeated margins.

- [ ] **Step 1: Prepare the Python environment.** Add `pdfplumber>=0.11,<0.12` to `ocr/requirements.txt` and install it in `.venv-ocr`; verify `rtk proxy .venv-ocr/bin/python -c 'import pdfplumber'` exits 0.
- [ ] **Step 2: Write failing tests.** A generated three-page fixture has a repeated top line, one unique bottom note, and a ruled table. Assert the repeated line disappears, the unique note stays, and table text is absent from `body`. Assert a one-page PDF keeps its top line.
- [ ] **Step 3: Verify red.** Run `rtk proxy .venv-ocr/bin/python -m unittest discover -s ocr -p 'test_pdf_extract.py' -v`; expect assertion failures for missing behavior.
- [ ] **Step 4: Implement `extract_pdf(data: bytes) -> dict`.** Use pdfplumber positions. Compare normalized lines only in the top/bottom 10%; remove a line only if it appears on at least 2 pages and at least 60% of pages. Normalize changing page numbers only in these margins. Mark textless pages `ocrRequired=true`; do not call OCR here.
- [ ] **Step 5: Verify green.** Run the same Python test command; expect all Task 1 tests to pass.

### Task 2: 표 행 Markdown 변환과 Python HTTP 경계

**Files:** Modify `ocr/pdf_extract.py`, `ocr/server.py`, `ocr/test_pdf_extract.py`, `ocr/test_server.py`.

**Interfaces:** `extract_pdf` emits `tables` entries `{"tableIndex": int, "rows": [{"rowIndex": int, "headers": list[str], "cells": list[str], "markdown": str, "extractionStatus": str}]}` per page. `headers` and `cells` are parallel arrays so Java can split a long row without parsing Markdown. `POST /pdf/extract` consumes `application/pdf` raw bytes up to 20MB and returns that JSON structure.

- [ ] **Step 1: Write failing tests.** Assert a ruled table becomes one Markdown record per logical body row with column labels, merged-cell group value, escaped `|`, and `<br>` for cell line breaks. If headings cannot be assigned to columns, assert the original cell text survives and `extractionStatus` is not `COMPLETE`. Assert the HTTP handler rejects empty, oversized, and non-PDF requests without echoing payloads.
- [ ] **Step 2: Verify red.** Run `rtk proxy .venv-ocr/bin/python -m unittest discover -s ocr -p 'test_*.py' -v`; expect failures in the new cases.
- [ ] **Step 3: Implement table rendering and `/pdf/extract`.** Use `page.find_tables()` and cell geometry; repeat trustworthy column labels and geometrically merged group values. Exclude table rectangles from `body`. Keep `/ocr` behavior and loopback binding unchanged.
- [ ] **Step 4: Verify green.** Run the same Python test command; expect all tests to pass.

### Task 3: Java PDF 추출 클라이언트

**Files:** Create `src/main/java/dev/springrag/dto/PdfPageDto.java`, `PdfTableRowDto.java`, `service/PdfExtractionClient.java`, `infrastructure/LocalPdfExtractionClient.java`, `src/test/java/dev/springrag/PdfExtractionClientTest.java`; modify `src/main/resources/application.yml`.

**Interfaces:** `PdfExtractionClient.extract(byte[] pdf) -> List<PdfPageDto>`. `PdfPageDto(int pageNumber, String body, boolean ocrRequired, List<PdfTableRowDto> tables)` and `PdfTableRowDto(int tableIndex, int rowIndex, List<String> headers, List<String> cells, String markdown, String extractionStatus)`; if a table has several rows, flatten them into the page's `tables` list. Configure `app.pdf-extraction.url` from `PDF_EXTRACTION_URL`, default `http://127.0.0.1:8092/pdf/extract`.

- [ ] **Step 1: Write failing tests.** Verify valid JSON decodes, missing or malformed page/table fields fail, HTTP error or timeout produces a typed `PdfExtractionUnavailableException`, and interruption restores the interrupt flag. Mock only `HttpClient.send` so the JSON parser and validation run without binding a local socket.
- [ ] **Step 2: Verify red.** Run `rtk proxy mvn -q -Dtest=PdfExtractionClientTest test`; expect failures for the absent client/validation.
- [ ] **Step 3: Implement the DTOs, interface, and HTTP client.** Send PDF bytes as `application/pdf`, enforce 20MB, validate response shape and page numbers, use bounded timeouts, and keep error messages free of PDF content. Add a package-private HTTP sender seam for the transport test and add the configuration property.
- [ ] **Step 4: Verify green.** Run the same Maven test command; expect pass.

### Task 4: 색인 연결·메타데이터·운영 문서

**Files:** Modify `src/main/java/dev/springrag/service/KnowledgeService.java`, `src/test/java/dev/springrag/KnowledgeExtractionTest.java`, `AGENTS.md`, `readme.md`, `runbooks/SpringRagLocalV0_1_0.md`.

**Interfaces:** `KnowledgeService` consumes `PdfExtractionClient.extract(byte[])`; body `Document` metadata adds `contentType=BODY`, table row adds `contentType=TABLE_ROW`, `tableIndex`, `rowIndex`, `tableExtractionStatus`. Existing common metadata and `page_number` remain.

- [ ] **Step 1: Write failing tests.** Assert body and table rows become distinct vector documents with correct page/order/ownership metadata, table text is not duplicated in body, and a textless page still uses OCR. Assert invalid extraction response leaves existing URL chunks and catalogue untouched. Assert long table rows retain column labels and row identity in every chunk and each chunk is at most 800 tokens.
- [ ] **Step 2: Verify red.** Run `rtk proxy mvn -q -Dtest=KnowledgeExtractionTest test`; expect new assertions to fail.
- [ ] **Step 3: Connect extraction and chunking.** Replace PDFBox text extraction with `PdfExtractionClient` output, retaining PDFBox rendering only for `ocrRequired` pages. A row under 800 tokens uses its `markdown` as one chunk; a longer row is split by `cells` into two-column Markdown `항목 | 내용` chunks that repeat its row identity and column label within the 800-token budget. Keep `VectorStore.add` and old-index swap ordering. Preserve existing constructors used by tests or update their callers in the same task.
- [ ] **Step 4: Update documents.** Specify the local PDF service, DTO/error behavior, new vector metadata, reindex procedure, and limits in README and versioned runbook; add pdfplumber to the recorded stack in `AGENTS.md`.
- [ ] **Step 5: Verify green.** Run focused Python and Java tests, `rtk proxy git diff --check`, then `rtk proxy mvn -q test`. If the known local socket or PDF-renderer failure recurs, report it separately and verify unaffected tests without claiming a green full suite. Inspect the approved sample PDF without calling OpenAI and confirm page/table counts and representative Markdown rows.

## Handoff

구현은 현재 작업 폴더에서 순서대로 진행한다. 기존 미커밋 OCR 파일을 보존하고, 사용자 요청 없이 커밋·푸시하거나 실제 문서를 재색인하지 않는다.
