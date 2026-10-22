package dev.springrag.infrastructure;

import dev.springrag.service.PdfExtractionClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExtractionClientTest {
    @Test void decodesBodyAndNestedTableRows() throws Exception {
        String body = """
            {"pages":[{"pageNumber":2,"body":"본문","ocrRequired":false,
              "tables":[{"tableIndex":1,"rows":[{"rowIndex":1,"headers":["직무"],
              "cells":["개발"],"markdown":"| 직무 |\\n| --- |\\n| 개발 |","extractionStatus":"COMPLETE"}]}]}]}
            """;

        var pages = new LocalPdfExtractionClient("http://127.0.0.1:8092/pdf/extract",
            request -> new LocalPdfExtractionClient.Response(200, body)).extract(new byte[] {1});

        assertEquals("본문", pages.get(0).body());
        assertEquals(2, pages.get(0).pageNumber());
        assertEquals("개발", pages.get(0).tables().get(0).cells().get(0));
    }

    @Test void rejectsMalformedResponseAndHttpFailure() throws Exception {
        var calls = new AtomicInteger();
        var client = new LocalPdfExtractionClient("http://127.0.0.1:8092/pdf/extract", request ->
            calls.getAndIncrement() == 0
                ? new LocalPdfExtractionClient.Response(200, "{\"pages\":[{\"body\":\"missing page number\"}]}")
                : new LocalPdfExtractionClient.Response(503, ""));

        assertThrows(PdfExtractionClient.PdfExtractionUnavailableException.class, () -> client.extract(new byte[] {1}));
        assertThrows(PdfExtractionClient.PdfExtractionUnavailableException.class, () -> client.extract(new byte[] {1}));
    }

    @Test void interruptionRestoresFlag() throws Exception {
        var client = new LocalPdfExtractionClient("http://127.0.0.1:8092/pdf/extract",
            request -> { throw new InterruptedException(); });

        try {
            assertThrows(PdfExtractionClient.PdfExtractionUnavailableException.class, () -> client.extract(new byte[] {1}));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
