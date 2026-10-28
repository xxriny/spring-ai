package dev.springrag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

class AnswerQualityEvaluationIT {
    @Test @DisplayName("저장된 답변을 Spring AI 관련성·사실성 평가기로 판정한다")
    void evaluateSavedAnswers() throws Exception {
        String input = System.getProperty("evaluation.input");
        Assumptions.assumeTrue(input != null, "-Devaluation.input is required");
        if (input.isBlank()) throw new IllegalArgumentException("-Devaluation.input is required");
        Path file = Path.of(input).toRealPath();
        Path results = Path.of("../evaluation/results").toRealPath();
        if (!file.startsWith(results) || !file.getFileName().toString().equals("answer.jsonl")) {
            throw new IllegalArgumentException("input must be evaluation/results/<run>/answer.jsonl");
        }
        String model = System.getenv().getOrDefault("OLLAMA_CHAT_MODEL", "qwen3.5:9b");
        String baseUrl = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434");
        var chatModel = OllamaChatModel.builder()
            .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
            .options(OllamaChatOptions.builder().model(model).disableThinking().build())
            .build();
        var relevance = new RelevancyEvaluator(ChatClient.builder(chatModel));
        var factual = FactCheckingEvaluator.builder(ChatClient.builder(chatModel)).build();
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> inputs = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (!line.isBlank()) inputs.add(json.readTree(line));
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("answer.jsonl is empty");
        Path output = file.getParent();
        String run = output.getFileName().toString();
        if (!run.matches("\\d{8}T\\d{6}Z")) throw new IllegalArgumentException("invalid evaluation run name");
        Path checkpoint = output.resolve("answer-evaluation.jsonl");
        Path modelFile = output.resolve("answer-evaluation-model.txt");
        if (Files.exists(modelFile) && !Files.readString(modelFile).equals(model)) {
            throw new IllegalArgumentException("evaluation model changed during resume");
        }
        List<AnswerQualityAssessment.Assessment> assessments = completed(checkpoint, inputs, json);
        if (!Files.exists(modelFile)) Files.writeString(modelFile, model);
        for (int index = assessments.size(); index < inputs.size(); index++) {
            var assessment = AnswerQualityAssessment.assess(inputs.get(index), relevance, factual);
            Files.writeString(checkpoint, json.writeValueAsString(assessment) + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            assessments.add(assessment);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("model", model);
        summary.put("total", assessments.size());
        summary.put("judged", assessments.stream().filter(a -> a.status().equals("judged")).count());
        summary.put("relevant", assessments.stream().filter(a -> Boolean.TRUE.equals(a.relevant())).count());
        summary.put("factual", assessments.stream().filter(a -> Boolean.TRUE.equals(a.factual())).count());
        summary.put("citationsStructurallyValid", assessments.stream().filter(AnswerQualityAssessment.Assessment::citationsValid).count());
        summary.put("noContext", assessments.stream().filter(a -> a.status().equals("no_context")).count());
        summary.put("searchError", assessments.stream().filter(a -> a.status().equals("search_error")).count());
        summary.put("generationError", assessments.stream().filter(a -> a.status().equals("generation_error")).count());
        json.writerWithDefaultPrettyPrinter().writeValue(output.resolve("answer-evaluation-summary.json").toFile(), summary);
        Path reports = Path.of("../docs/evaluations");
        Files.createDirectories(reports);
        Files.writeString(reports.resolve("SpringAiAnswerQuality-" + run + ".md"),
            AnswerQualityAssessment.markdownReport(run, model,
                json.readTree(output.resolve("config.json").toFile()),
                json.readTree(output.resolve("summary.json").toFile()), assessments));
    }

    static List<AnswerQualityAssessment.Assessment> completed(Path checkpoint, List<JsonNode> inputs,
        ObjectMapper json) throws Exception {
        List<AnswerQualityAssessment.Assessment> assessments = new ArrayList<>();
        if (!Files.exists(checkpoint)) return assessments;
        for (String line : Files.readAllLines(checkpoint)) {
            if (line.isBlank()) continue;
            var assessment = json.readValue(line, AnswerQualityAssessment.Assessment.class);
            if (assessments.size() >= inputs.size()
                || !assessment.id().equals(inputs.get(assessments.size()).path("id").asText())) {
                throw new IllegalArgumentException("saved assessments do not match answer order");
            }
            assessments.add(assessment);
        }
        return assessments;
    }
}
