package dev.springrag.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;

/** 저장된 답변과 검색 근거를 Spring AI 평가 요청으로 변환한다. */
final class AnswerQualityAssessment {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+)]");

    private AnswerQualityAssessment() {}

    static Assessment assess(JsonNode row, Evaluator relevance, Evaluator factual) {
        String id = row.path("id").asText();
        if (row.has("error")) {
            return new Assessment(id, "search".equals(row.path("stage").asText()) ? "search_error" : "generation_error",
                null, null, false);
        }
        JsonNode passages = row.path("passages");
        if (!passages.isArray() || passages.isEmpty()) {
            return new Assessment(id, "no_context", null, null, false);
        }
        List<Document> documents = new ArrayList<>();
        for (JsonNode passage : passages) {
            String text = passage.path("text").asText();
            if (text.isBlank()) throw new IllegalArgumentException("passage text is required: " + id);
            documents.add(new Document(text));
        }
        String query = row.path("query").asText();
        String answer = row.path("answer").asText();
        if (query.isBlank() || answer.isBlank()) throw new IllegalArgumentException("query and answer are required: " + id);
        EvaluationRequest request = new EvaluationRequest(query, documents, answer);
        return new Assessment(id, "judged", relevance.evaluate(request).isPass(),
            factual.evaluate(request).isPass(), citationsValid(answer, passages, row.path("sources")));
    }

    private static boolean citationsValid(String answer, JsonNode passages, JsonNode sources) {
        if (!sources.isArray() || sources.size() != passages.size()) return false;
        for (int i = 0; i < passages.size(); i++) {
            String documentId = passages.get(i).path("documentId").asText();
            if (documentId.isBlank() || !documentId.equals(sources.get(i).path("documentId").asText())) {
                return false;
            }
        }
        Matcher matcher = CITATION.matcher(answer);
        boolean found = false;
        while (matcher.find()) {
            found = true;
            try {
                int number = Integer.parseInt(matcher.group(1));
                if (number < 1 || number > passages.size()) return false;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return found;
    }

    static String markdownReport(String run, String model, JsonNode config, JsonNode search,
        List<Assessment> assessments) {
        long judged = assessments.stream().filter(a -> a.status().equals("judged")).count();
        long relevant = assessments.stream().filter(a -> Boolean.TRUE.equals(a.relevant())).count();
        long factual = assessments.stream().filter(a -> Boolean.TRUE.equals(a.factual())).count();
        long citations = assessments.stream().filter(Assessment::citationsValid).count();
        long noContext = assessments.stream().filter(a -> a.status().equals("no_context")).count();
        long searchError = assessments.stream().filter(a -> a.status().equals("search_error")).count();
        long generationError = assessments.stream().filter(a -> a.status().equals("generation_error")).count();
        StringBuilder report = new StringBuilder("# Spring AI 답변 품질 평가: ").append(run).append("\n\n")
            .append("- 상태: 평가 실행 완료\n- 질문 세트: 공식 Spring AI 문서 4쪽, 고정 질문 16개 (`evaluation/questions.json`)\n")
            .append("- 평가 모델: Ollama `").append(model).append("`\n")
            .append("- 평가 방식: Spring AI `RelevancyEvaluator`, `FactCheckingEvaluator`, 인용 구조 검사. ")
            .append("판정 기준과 한계는 [평가 방법](../AnswerQualityEvaluation.md)을 따른다.\n\n")
            .append("## 실행 설정\n\n| 항목 | 값 |\n| --- | --- |\n")
            .append("| 임베딩 | ").append(config.path("embeddingModel").asText()).append(" |\n")
            .append("| 벡터 후보 Top-k / 유사도 기준 | ").append(config.path("topK").asText())
            .append(" / ").append(config.path("threshold").asText()).append(" |\n")
            .append("| Sparse 검색 | ").append(config.path("bm25").asText()).append(" |\n")
            .append("| 결합 | ").append(config.path("fusion").asText()).append(" |\n")
            .append("| 리랭커 | ").append(config.path("rerankerModel").asText()).append(" |\n")
            .append("| 최종 passage Top-k | ").append(config.path("passageTopK").asText()).append(" |\n\n")
            .append("## 검색 지표\n\n근거 문구가 기대 문서의 청크에 포함됐는지 측정한 결과이며 답변 품질 지표가 아니다.\n\n")
            .append("| 단계 | 근거 문구 Hit@3 | MRR |\n| --- | ---: | ---: |\n");
        JsonNode searchAll = search.path("all");
        int searched = searchAll.path("n").asInt();
        String[] stages = {"vectorCandidates", "bm25Candidates", "candidates", "reranked", "passages"};
        String[] names = {"벡터 후보", "BM25 후보", "RRF 결합", "Qwen 재정렬", "최종 passage"};
        for (int index = 0; index < stages.length; index++) {
            JsonNode chunk = searchAll.path(stages[index]).path("chunk");
            double hit = chunk.path("hit@3").asDouble();
            report.append("| ").append(names[index]).append(" | ")
                .append(Math.round(hit * searched)).append('/').append(searched)
                .append(" (").append(String.format(Locale.ROOT, "%.1f", hit * 100)).append("%) | ")
                .append(String.format(Locale.ROOT, "%.3f", chunk.path("mrr").asDouble())).append(" |\n");
        }
        report.append("\n## 답변 품질 집계\n\n| 항목 | 결과 |\n| --- | ---: |\n")
            .append("| 전체 | ").append(assessments.size()).append(" |\n")
            .append("| 평가 완료 | ").append(judged).append(" |\n")
            .append("| 관련성 | ").append(ratio(relevant, judged)).append(" |\n")
            .append("| 사실성 | ").append(ratio(factual, judged)).append(" |\n")
            .append("| 인용 구조 | ").append(ratio(citations, judged)).append(" |\n")
            .append("| 근거 없음 | ").append(noContext).append(" |\n")
            .append("| 검색 실패 | ").append(searchError).append(" |\n")
            .append("| 답변 생성 실패 | ").append(generationError).append(" |\n\n")
            .append("## 문항별 판정\n\n| 문항 ID | 상태 | 관련성 | 사실성 | 인용 구조 |\n")
            .append("| --- | --- | --- | --- | --- |\n");
        for (Assessment assessment : assessments) {
            report.append("| ").append(assessment.id().replace("|", "\\|"))
                .append(" | ").append(assessment.status())
                .append(" | ").append(label(assessment.relevant()))
                .append(" | ").append(label(assessment.factual()))
                .append(" | ").append(assessment.status().equals("judged")
                    ? (assessment.citationsValid() ? "통과" : "실패") : "미평가").append(" |\n");
        }
        return report.toString();
    }

    private static String label(Boolean passed) {
        return passed == null ? "미평가" : passed ? "통과" : "실패";
    }

    private static String ratio(long passed, long judged) {
        return judged == 0 ? "미측정" : passed + "/" + judged;
    }

    record Assessment(String id, String status, Boolean relevant, Boolean factual, boolean citationsValid) {}
}
