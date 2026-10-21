package dev.springrag.service;

import dev.springrag.dto.CrawlResultDto;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SiteCrawler {
    private static final int BYTE_LIMIT = 20 * 1024 * 1024;
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final KnowledgeService knowledge;
    private final Fetcher fetcher;

    @FunctionalInterface public interface Fetcher { Fetched fetch(URI url) throws IOException, InterruptedException; }
    public record Fetched(int status, String contentType, String location, byte[] bytes) {}
    private record Pending(URI url, int redirects) {}
    @Autowired public SiteCrawler(KnowledgeService knowledge) { this(knowledge, SiteCrawler::fetch); }
    public SiteCrawler(KnowledgeService knowledge, Fetcher fetcher) {
        this.knowledge = knowledge;
        this.fetcher = fetcher;
    }

    public CrawlResultDto crawl(String seedUrl, String category) { return crawl(seedUrl, category, 100); }

    public CrawlResultDto crawl(String seedUrl, String category, int maxPages) {
        if (maxPages < 1 || maxPages > 100) throw new IllegalArgumentException("maxPages는 1~100이어야 합니다");
        category = KnowledgeService.normalizeCategory(category);
        CrawlPolicy policy = new CrawlPolicy(URI.create(seedUrl));
        var pending = new ArrayDeque<Pending>();
        var visited = new HashSet<URI>();
        var errors = new ArrayList<String>();
        int indexed = 0;
        int skipped = 0;
        int pagesFetched = 0;
        pending.add(new Pending(CrawlPolicy.normalize(URI.create(seedUrl)), 0));
        while (!pending.isEmpty() && pagesFetched < maxPages) {
            Pending current = pending.removeFirst();
            URI url = current.url();
            if (!visited.add(url) || !policy.accepts(url)) continue;
            try {
                Fetched response = fetcher.fetch(url);
                if (response.status() >= 300 && response.status() < 400) {
                    if (current.redirects() >= 10) { errors.add(url + ": 리다이렉트 횟수 초과"); skipped++; continue; }
                    URI redirect = CrawlPolicy.normalize(url.resolve(response.location()));
                    if (policy.accepts(redirect) && !visited.contains(redirect))
                        pending.addFirst(new Pending(redirect, current.redirects() + 1));
                    else { errors.add(url + ": 범위 밖 또는 순환 리다이렉트"); skipped++; }
                    continue;
                }
                pagesFetched++;
                if (response.status() != 200) { errors.add(url + ": HTTP " + response.status()); skipped++; continue; }
                if (response.bytes().length > BYTE_LIMIT) { errors.add(url + ": 파일 크기 초과"); skipped++; continue; }
                boolean html = response.contentType().toLowerCase().contains("text/html") || url.getPath().endsWith(".html") || url.getPath().endsWith(".htm");
                boolean file = url.getPath().matches("(?i).*\\.(pdf|doc|docx|txt)$");
                if (!html && !file) { errors.add(url + ": 지원하지 않는 형식"); skipped++; continue; }
                String title = url.getPath().substring(url.getPath().lastIndexOf('/') + 1);
                if (html) {
                    var page = Jsoup.parse(new String(response.bytes(), java.nio.charset.StandardCharsets.UTF_8), url.toString());
                    if (!page.title().isBlank()) title = page.title();
                    for (var link : page.select("a[href]")) {
                        try {
                            URI next = CrawlPolicy.normalize(url.resolve(link.attr("href")));
                            if (policy.accepts(next) && !visited.contains(next)) pending.addLast(new Pending(next, 0));
                        } catch (IllegalArgumentException ignored) { /* malformed link */ }
                    }
                }
                knowledge.ingestUrl(response.bytes(), title.isBlank() ? url.toString() : title, url.toString(), category);
                indexed++;
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                errors.add(url + ": " + e.getMessage());
                skipped++;
            }
        }
        return new CrawlResultDto(indexed, skipped, errors);
    }

    private static Fetched fetch(URI url) throws IOException, InterruptedException {
        // shortcut: DNS may change before HttpClient connects; add egress filtering before accepting untrusted crawl administrators.
        new CrawlPolicy(url).requirePublicResolution(url);
        var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(15))
            .header("User-Agent", "SpringRagBot/0.1").GET().build();
        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        byte[] bytes;
        try (var body = response.body()) { bytes = body.readNBytes(BYTE_LIMIT + 1); }
        return new Fetched(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
            response.headers().firstValue("Location").orElse(""), bytes);
    }
}
