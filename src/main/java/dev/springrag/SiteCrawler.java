package dev.springrag;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

@Service
class SiteCrawler {
    private final KnowledgeService knowledge;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5)).build();

    SiteCrawler(KnowledgeService knowledge) { this.knowledge = knowledge; }

    record Result(int indexed, int skipped, List<String> errors) {}

    Result crawl(String seedUrl) {
        CrawlPolicy policy = new CrawlPolicy(URI.create(seedUrl));
        var pending = new ArrayDeque<URI>();
        var visited = new HashSet<URI>();
        var errors = new ArrayList<String>();
        int indexed = 0;
        int skipped = 0;
        pending.add(CrawlPolicy.normalize(URI.create(seedUrl)));
        while (!pending.isEmpty() && visited.size() < 100) {
            URI url = pending.removeFirst();
            if (!visited.add(url) || !policy.accepts(url)) continue;
            try {
                policy.requirePublicResolution(url);
                var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "SpringRagBot/0.1").GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 300 && response.statusCode() < 400) {
                    String location = response.headers().firstValue("Location").orElse("");
                    URI redirect = CrawlPolicy.normalize(url.resolve(location));
                    if (policy.accepts(redirect)) pending.addFirst(redirect);
                    else skipped++;
                    response.body().close();
                    continue;
                }
                if (response.statusCode() != 200) { skipped++; response.body().close(); continue; }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                int limit = 20 * 1024 * 1024;
                byte[] bytes;
                try (var body = response.body()) {
                    bytes = body.readNBytes(limit + 1);
                }
                if (bytes.length > limit) { skipped++; continue; }
                boolean html = contentType.contains("text/html") || url.getPath().endsWith(".html") || url.getPath().endsWith(".htm");
                boolean file = url.getPath().matches("(?i).*\\.(pdf|doc|docx|txt)$");
                if (!html && !file) { skipped++; continue; }
                String title = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
                if (html) {
                    var page = Jsoup.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8), url.toString());
                    if (!page.title().isBlank()) title = page.title();
                    for (var link : page.select("a[href]")) {
                        try {
                            URI next = CrawlPolicy.normalize(url.resolve(link.attr("href")));
                            if (policy.accepts(next) && !visited.contains(next)) pending.addLast(next);
                        } catch (IllegalArgumentException ignored) { /* malformed link */ }
                    }
                }
                knowledge.ingestUrl(bytes, title.isBlank() ? url.toString() : title, url.toString());
                indexed++;
            } catch (IOException | InterruptedException | IllegalArgumentException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                errors.add(url + ": " + e.getMessage());
            }
        }
        return new Result(indexed, skipped, errors);
    }
}
