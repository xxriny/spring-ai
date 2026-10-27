package dev.springrag.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.springrag.knowledge.dto.PdfPageDto;
import dev.springrag.knowledge.dto.PdfTableRowDto;
import dev.springrag.knowledge.port.PdfExtractionClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalPdfExtractionClient implements PdfExtractionClient {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final URI url;
    private final Sender sender;
    private final ObjectMapper json = new ObjectMapper();

    record Response(int status, String body) {}
    @FunctionalInterface interface Sender { Response send(HttpRequest request) throws Exception; }

    @Autowired public LocalPdfExtractionClient(@Value("${app.pdf-extraction.url}") String url) {
        this(url, request -> {
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        });
    }

    LocalPdfExtractionClient(String url, Sender sender) {
        this.url = URI.create(url);
        this.sender = sender;
    }

    @Override public List<PdfPageDto> extract(byte[] pdf) {
        if (pdf == null || pdf.length == 0 || pdf.length > 20_000_000)
            throw new IllegalArgumentException("PDF는 1~20MB여야 합니다");
        try {
            var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/pdf")
                .POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build();
            var response = sender.send(request);
            if (response.status() != 200) throw new IllegalStateException("HTTP " + response.status());
            return parse(json.readTree(response.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PdfExtractionUnavailableException("PDF 추출 호출 중단", e);
        } catch (Exception e) {
            throw new PdfExtractionUnavailableException("PDF 추출 서비스를 사용할 수 없습니다", e);
        }
    }

    private static List<PdfPageDto> parse(JsonNode root) {
        JsonNode pages = root.path("pages");
        if (!pages.isArray() || pages.isEmpty()) throw new IllegalArgumentException("잘못된 PDF 추출 응답");
        List<PdfPageDto> result = new ArrayList<>();
        for (JsonNode page : pages) {
            JsonNode number = page.path("pageNumber");
            JsonNode body = page.path("body");
            JsonNode ocr = page.path("ocrRequired");
            JsonNode tables = page.path("tables");
            if (!number.isIntegralNumber() || number.intValue() < 1 || !body.isTextual()
                || !ocr.isBoolean() || !tables.isArray()) throw new IllegalArgumentException("잘못된 PDF 페이지 응답");
            List<PdfTableRowDto> rows = new ArrayList<>();
            for (JsonNode table : tables) {
                JsonNode tableIndex = table.path("tableIndex");
                JsonNode tableRows = table.path("rows");
                if (!tableIndex.isIntegralNumber() || tableIndex.intValue() < 1 || !tableRows.isArray())
                    throw new IllegalArgumentException("잘못된 PDF 표 응답");
                for (JsonNode row : tableRows) {
                    JsonNode rowIndex = row.path("rowIndex");
                    JsonNode headers = row.path("headers");
                    JsonNode cells = row.path("cells");
                    JsonNode markdown = row.path("markdown");
                    JsonNode status = row.path("extractionStatus");
                    if (!rowIndex.isIntegralNumber() || rowIndex.intValue() < 1 || !headers.isArray()
                        || !cells.isArray() || headers.size() == 0 || headers.size() != cells.size()
                        || !markdown.isTextual() || !status.isTextual())
                        throw new IllegalArgumentException("잘못된 PDF 표 행 응답");
                    rows.add(new PdfTableRowDto(tableIndex.intValue(), rowIndex.intValue(),
                        strings(headers), strings(cells), markdown.asText(), status.asText()));
                }
            }
            result.add(new PdfPageDto(number.intValue(), body.asText(), ocr.booleanValue(), rows));
        }
        return result;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (!value.isTextual()) throw new IllegalArgumentException("잘못된 PDF 표 셀 응답");
            values.add(value.asText());
        }
        return values;
    }
}
