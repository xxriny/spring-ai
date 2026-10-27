package dev.springrag.infrastructure;

import dev.springrag.retrieval.port.Reranker;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class QwenReranker implements Reranker {
    private final URI url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public QwenReranker(@Value("${app.reranker.url}") String url) { this.url = URI.create(url); }

    @Override public List<Double> scores(String query, List<String> passages) {
        if (passages.isEmpty() || passages.size() > 10) throw new IllegalArgumentException("리랭킹 후보는 1~10개여야 합니다");
        try {
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query, "documents", passages))))
                .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            return parseScores(response.body(), passages.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RerankerUnavailableException("리랭커 호출 중단", e);
        } catch (Exception e) {
            throw new RerankerUnavailableException("리랭커를 사용할 수 없습니다", e);
        }
    }

    public List<Double> parseScores(String body, int count) {
        try {
            var node = json.readTree(body).path("scores");
            if (!node.isArray() || node.size() != count) throw new IllegalStateException("점수 개수 불일치");
            List<Double> scores = new ArrayList<>();
            for (var score : node) {
                if (!score.isNumber() || !Double.isFinite(score.doubleValue())) throw new IllegalStateException("잘못된 점수");
                scores.add(score.doubleValue());
            }
            return scores;
        } catch (Exception e) {
            throw new RerankerUnavailableException("잘못된 리랭커 응답", e);
        }
    }
}
