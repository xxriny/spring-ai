package dev.springrag.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.springrag.knowledge.port.OcrClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PaddleOcrClient implements OcrClient {
    private final URI url;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();

    public PaddleOcrClient(@Value("${app.ocr.url}") String url) { this.url = URI.create(url); }

    @Override public String recognize(byte[] image) {
        try {
            var payload = json.writeValueAsString(Map.of("image", Base64.getEncoder().encodeToString(image)));
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
            var value = json.readTree(response.body()).path("text");
            if (!value.isTextual()) throw new IllegalStateException("잘못된 OCR 응답");
            return value.asText();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OcrUnavailableException("OCR 호출 중단", e);
        } catch (Exception e) {
            throw new OcrUnavailableException("OCR을 사용할 수 없습니다", e);
        }
    }
}
