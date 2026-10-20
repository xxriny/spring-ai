package dev.springrag;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import org.junit.jupiter.api.Test;

class CrawlPolicyTest {
    @Test void confinesLinksToSeedPathAndRejectsPrivateTargets() {
        CrawlPolicy policy = new CrawlPolicy(URI.create("https://docs.example.org/guide/"));
        assertTrue(policy.accepts(URI.create("https://docs.example.org/guide/setup?lang=ko#top")));
        assertFalse(policy.accepts(URI.create("https://docs.example.org/admin")));
        assertFalse(policy.accepts(URI.create("https://evil.example.org/guide/")));
        assertFalse(policy.accepts(URI.create("http://127.0.0.1/guide/")));
    }

    @Test void normalizesFragmentsForDeduplication() {
        assertEquals("https://docs.example.org/guide/page", CrawlPolicy.normalize(URI.create("https://docs.example.org/guide/page#one")).toString());
    }

    @Test void directorySeedWithoutSlashStaysWithinDirectory() {
        CrawlPolicy policy = new CrawlPolicy(URI.create("https://docs.example.org/guide"));
        assertTrue(policy.accepts(URI.create("https://docs.example.org/guide")));
        assertTrue(policy.accepts(URI.create("https://docs.example.org/guide/start")));
        assertFalse(policy.accepts(URI.create("https://docs.example.org/guidebook")));
    }
}
