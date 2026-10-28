package dev.springrag.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

class AnswerQualityAssessmentTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test @DisplayName("실제 답변과 최종 passage를 Spring AI 평가 요청에 전달한다")
    void passesAnswerAndPassagesToEvaluators() throws Exception {
        var row = json.readTree("""
            {"id":"etl-03","query":"Which reader extracts PDF pages?",
             "answer":"PagePdfDocumentReader reads PDF pages [1].",
             "passages":[{"text":"PagePdfDocumentReader uses PDFBox","documentId":"doc-1"}],
             "sources":[{"documentId":"doc-1"}]}
            """);
        AtomicReference<EvaluationRequest> seen = new AtomicReference<>();
        Evaluator relevant = request -> { seen.set(request); return new EvaluationResponse(true, "", Map.of()); };
        Evaluator factual = request -> new EvaluationResponse(true, "", Map.of());

        var result = AnswerQualityAssessment.assess(row, relevant, factual);

        assertEquals("judged", result.status());
        assertTrue(result.relevant());
        assertTrue(result.factual());
        assertTrue(result.citationsValid());
        assertEquals("Which reader extracts PDF pages?", seen.get().getUserText());
        assertEquals("PagePdfDocumentReader reads PDF pages [1].", seen.get().getResponseContent());
        assertEquals("PagePdfDocumentReader uses PDFBox", seen.get().getDataList().get(0).getText());
    }

    @Test @DisplayName("근거가 없거나 생성이 실패하면 평가 모델을 호출하지 않는다")
    void skipsMissingContextAndGenerationErrors() throws Exception {
        Evaluator unused = request -> { throw new AssertionError("평가 모델 호출됨"); };
        var empty = json.readTree("""
            {"id":"empty","query":"question","answer":"근거가 없습니다","passages":[],"sources":[]}
            """);
        var failed = json.readTree("{\"id\":\"failed\",\"error\":\"model unavailable\"}");
        var searchFailed = json.readTree("{\"id\":\"search-failed\",\"stage\":\"search\",\"error\":\"retrieval unavailable\"}");

        assertEquals("no_context", AnswerQualityAssessment.assess(empty, unused, unused).status());
        assertEquals("generation_error", AnswerQualityAssessment.assess(failed, unused, unused).status());
        assertEquals("search_error", AnswerQualityAssessment.assess(searchFailed, unused, unused).status());
    }

    @Test @DisplayName("출처 번호와 문서 식별자가 passage와 다르면 실패로 기록한다")
    void rejectsMismatchedCitations() throws Exception {
        var row = json.readTree("""
            {"id":"q1","query":"question","answer":"답변 [2]",
             "passages":[{"text":"근거","documentId":"doc-1"}],
             "sources":[{"documentId":"other"}]}
            """);
        Evaluator pass = request -> new EvaluationResponse(true, "", Map.of());

        assertFalse(AnswerQualityAssessment.assess(row, pass, pass).citationsValid());
    }

    @Test @DisplayName("인용이 없거나 범위를 벗어나면 구조 검사에 실패한다")
    void rejectsMissingAndOutOfRangeCitations() throws Exception {
        Evaluator pass = request -> new EvaluationResponse(true, "", Map.of());
        String template = """
            {"id":"q2","query":"question","answer":"%s",
             "passages":[{"text":"근거","documentId":"doc-1"}],
             "sources":[{"documentId":"doc-1"}]}
            """;

        assertFalse(AnswerQualityAssessment.assess(json.readTree(template.formatted("인용 없음")), pass, pass).citationsValid());
        assertFalse(AnswerQualityAssessment.assess(json.readTree(template.formatted("잘못된 번호 [999999999999999999999]")), pass, pass).citationsValid());
    }

    @Test @DisplayName("평가 보고서에는 문항별 판정과 집계를 남기고 답변 원문은 제외한다")
    void rendersTrackedMarkdownReport() throws Exception {
        var assessments = List.of(
            new AnswerQualityAssessment.Assessment("etl-01", "judged", true, false, true),
            new AnswerQualityAssessment.Assessment("etl-02", "no_context", null, null, false));
        var config = json.readTree("""
            {"embeddingModel":"qwen3-embedding:8b/1536","rerankerModel":"Qwen/Qwen3-Reranker-0.6B",
             "topK":10,"threshold":0.3,"bm25":"pg_search 0.26.0","fusion":"RRF k=60","passageTopK":3}
            """);
        var search = json.readTree("{" +
            "\"all\":{\"n\":16,\"passages\":{\"chunk\":{\"hit@3\":0.875,\"mrr\":0.698}}}}");

        String report = AnswerQualityAssessment.markdownReport("20261027T000000Z", "qwen3.5:9b", config, search, assessments);

        assertTrue(report.contains("| etl-01 | judged | 통과 | 실패 | 통과 |"));
        assertTrue(report.contains("| etl-02 | no_context | 미평가 | 미평가 | 미평가 |"));
        assertTrue(report.contains("관련성 | 1/1"));
        assertTrue(report.contains("qwen3-embedding:8b/1536"));
        assertTrue(report.contains("14/16 (87.5%)"));
        assertFalse(report.contains("answer.jsonl"));
        assertTrue(AnswerQualityAssessment.markdownReport("20261027T000000Z", "qwen3.5:9b", config, search,
            List.of(new AnswerQualityAssessment.Assessment("etl-03", "no_context", null, null, false)))
            .contains("관련성 | 미측정"));
    }

    @Test @DisplayName("중단된 평가의 완료 문항만 읽고 질문 순서가 다르면 거부한다")
    void resumesOnlyMatchingAssessmentPrefix(@TempDir Path directory) throws Exception {
        Path checkpoint = directory.resolve("answer-evaluation.jsonl");
        Files.writeString(checkpoint, "{\"id\":\"q1\",\"status\":\"judged\",\"relevant\":true,\"factual\":false,\"citationsValid\":true}\n");
        var input = List.of(json.readTree("{\"id\":\"q1\"}"), json.readTree("{\"id\":\"q2\"}"));

        assertEquals(1, AnswerQualityEvaluationIT.completed(checkpoint, input, json).size());
        assertEquals("q1", AnswerQualityEvaluationIT.completed(checkpoint, input, json).get(0).id());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> AnswerQualityEvaluationIT.completed(checkpoint, List.of(input.get(1)), json));
    }
}
