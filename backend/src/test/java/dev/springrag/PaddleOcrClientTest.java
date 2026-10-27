package dev.springrag;

import com.sun.net.httpserver.HttpServer;
import dev.springrag.infrastructure.PaddleOcrClient;
import dev.springrag.knowledge.port.OcrClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaddleOcrClientTest {
    @Test void acceptsTextAndRejectsMalformedResponse() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var malformed = new AtomicBoolean();
        server.createContext("/ocr", exchange -> {
            byte[] body = (malformed.get() ? "{}" : "{\"text\":\"스캔 근거\"}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var client = new PaddleOcrClient("http://127.0.0.1:" + server.getAddress().getPort() + "/ocr");
            assertEquals("스캔 근거", client.recognize(new byte[] {1, 2, 3}));
            malformed.set(true);
            assertThrows(OcrClient.OcrUnavailableException.class, () -> client.recognize(new byte[] {1}));
        } finally {
            server.stop(0);
        }
    }
}
