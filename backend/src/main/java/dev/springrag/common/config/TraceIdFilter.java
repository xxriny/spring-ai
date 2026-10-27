package dev.springrag.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TraceIdFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader("X-Trace-Id");
        String traceId;
        try { traceId = UUID.fromString(incoming).toString(); }
        catch (RuntimeException e) { traceId = UUID.randomUUID().toString(); }
        response.setHeader("X-Trace-Id", traceId);
        MDC.put("traceId", traceId);
        try { chain.doFilter(request, response); }
        finally { MDC.remove("traceId"); }
    }
}
