package dev.springrag;

import dev.springrag.dto.DocumentDto;
import dev.springrag.service.KnowledgeService;
import dev.springrag.service.SiteCrawler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SiteCrawlerTest {
    @Test void pageLimitKeepsSeedOnlyAndCategoryFollowsChild() {
        List<String> indexed = new ArrayList<>();
        KnowledgeService knowledge = new KnowledgeService(null, null, null, "/tmp") {
            @Override public DocumentDto ingestUrl(byte[] bytes, String title, String url, String category) {
                indexed.add(url + ":" + category);
                return null;
            }
        };
        SiteCrawler.Fetcher fetcher = url -> {
            String html = url.getPath().endsWith("next.html")
                ? "<html><body><main>child content</main></body></html>"
                : "<html><body><main>seed content<a href='next.html'>next</a></main></body></html>";
            return new SiteCrawler.Fetched(200, "text/html", "", html.getBytes(StandardCharsets.UTF_8));
        };
        var crawler = new SiteCrawler(knowledge, fetcher);

        assertEquals(1, crawler.crawl("https://docs.example.org/guide/", "Guide", 1).indexed());
        assertEquals(List.of("https://docs.example.org/guide/:Guide"), indexed);
        indexed.clear();
        assertEquals(2, crawler.crawl("https://docs.example.org/guide/", "Guide", 2).indexed());
        assertEquals(List.of("https://docs.example.org/guide/:Guide", "https://docs.example.org/guide/next.html:Guide"), indexed);
    }

    @Test void invalidPageLimitStopsBeforeFetching() {
        var crawler = new SiteCrawler(new KnowledgeService(null, null, null, "/tmp"),
            url -> { throw new AssertionError("HTTP 호출됨"); });
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl("https://docs.example.org/guide/", "Guide", 0));
        assertThrows(IllegalArgumentException.class, () -> crawler.crawl("https://docs.example.org/guide/", "Guide", 101));
    }

    @Test void externalRedirectIsNeverFetched() {
        List<URI> requested = new ArrayList<>();
        var crawler = new SiteCrawler(new KnowledgeService(null, null, null, "/tmp"), url -> {
            requested.add(url);
            return new SiteCrawler.Fetched(302, "text/html", "https://evil.example.org/private", new byte[0]);
        });
        assertEquals(0, crawler.crawl("https://docs.example.org/guide/", "Guide", 2).indexed());
        assertEquals(1, requested.size());
    }

    @Test void failedPageIsReportedAndSkipped() {
        var crawler = new SiteCrawler(new KnowledgeService(null, null, null, "/tmp"),
            url -> new SiteCrawler.Fetched(503, "text/html", "", new byte[0]));
        var result = crawler.crawl("https://docs.example.org/guide/", "Guide", 1);
        assertEquals(0, result.indexed());
        assertEquals(1, result.skipped());
        assertEquals(1, result.errors().size());
    }

    @Test void sameScopeRedirectDoesNotConsumePageLimit() {
        List<URI> requested = new ArrayList<>();
        var knowledge = new KnowledgeService(null, null, null, "/tmp") {
            @Override public DocumentDto ingestUrl(byte[] bytes, String title, String url, String category) { return null; }
        };
        var crawler = new SiteCrawler(knowledge, url -> {
            requested.add(url);
            return url.getPath().endsWith("old.html")
                ? new SiteCrawler.Fetched(302, "text/html", "new.html", new byte[0])
                : new SiteCrawler.Fetched(200, "text/html", "", "<main><p>real page text</p></main>".getBytes());
        });
        var result = crawler.crawl("https://docs.example.org/guide/old.html", "Guide", 1);
        assertEquals(1, result.indexed());
        assertEquals(2, requested.size());
    }

    @Test void ingestionFailureReportsUrlAndContinuesToChild() {
        List<String> indexed = new ArrayList<>();
        var knowledge = new KnowledgeService(null, null, null, "/tmp") {
            @Override public DocumentDto ingestUrl(byte[] bytes, String title, String url, String category) {
                if (url.endsWith("seed.html")) throw new IllegalStateException("embedding unavailable");
                indexed.add(url);
                return null;
            }
        };
        var crawler = new SiteCrawler(knowledge, url -> new SiteCrawler.Fetched(200, "text/html", "",
            "<main><p>body</p><a href='child.html'>next</a></main>".getBytes()));
        var result = crawler.crawl("https://docs.example.org/guide/seed.html", "Guide", 2);
        assertEquals(1, result.indexed());
        assertEquals(1, result.skipped());
        assertTrue(result.errors().get(0).contains("seed.html"));
        assertEquals(List.of("https://docs.example.org/guide/child.html"), indexed);
    }

    @Test void redirectChainIsBounded() {
        List<URI> requested = new ArrayList<>();
        var crawler = new SiteCrawler(new KnowledgeService(null, null, null, "/tmp"), url -> {
            requested.add(url);
            int page = Integer.parseInt(url.getPath().replace("/guide/", "").replace(".html", ""));
            return new SiteCrawler.Fetched(302, "text/html", (page + 1) + ".html", new byte[0]);
        });
        var result = crawler.crawl("https://docs.example.org/guide/0.html", "Guide", 1);
        assertEquals(0, result.indexed());
        assertEquals(1, result.skipped());
        assertTrue(result.errors().get(0).contains("리다이렉트 횟수 초과"));
        assertEquals(11, requested.size());
    }
}
