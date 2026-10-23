package dev.springrag;

import dev.springrag.config.TraceIdFilter;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {
    @Test void propagatesValidTraceIdAndClearsItAfterRequest() throws Exception {
        String incoming = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", incoming);
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) -> assertEquals(incoming, MDC.get("traceId")));
        assertEquals(incoming, response.getHeader("X-Trace-Id"));
        assertNull(MDC.get("traceId"));
    }

    @Test void replacesInvalidTraceId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("X-Trace-Id", "untrusted\ntext");
        var response = new MockHttpServletResponse();
        new TraceIdFilter().doFilter(request, response, (req, res) ->
            assertDoesNotThrow(() -> UUID.fromString(MDC.get("traceId"))));
        assertDoesNotThrow(() -> UUID.fromString(response.getHeader("X-Trace-Id")));
    }
}
