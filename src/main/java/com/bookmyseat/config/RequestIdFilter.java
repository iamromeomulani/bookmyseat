package com.bookmyseat.config;

import static net.logstash.logback.argument.StructuredArguments.kv;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request a correlation id and writes one structured access-log line per request.
 *
 * - The id is taken from the caller's X-Request-Id header (if it looks sane) or generated.
 * - It is placed in the logging MDC, so EVERY log line written while handling the request
 *   carries "request_id", and it is returned in the X-Request-Id response header.
 * - Runs first (highest precedence), so even requests rejected by security (401/403) are logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "request_id";
    public static final String ATTR_USER_ID = "log.user_id";
    public static final String ATTR_OUTCOME = "log.outcome";

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = (incoming != null && SAFE_ID.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            String path = request.getRequestURI();
            boolean noisy = path.startsWith("/actuator") || path.equals("/health")
                    || path.equals("/ready") || path.equals("/metrics");
            if (noisy) {
                log.debug("request completed {} {} -> {}", request.getMethod(), path, response.getStatus());
            } else {
                log.info("request completed",
                        kv("method", request.getMethod()),
                        kv("path", path),
                        kv("status", response.getStatus()),
                        kv("duration_ms", durationMs),
                        kv("user_id", request.getAttribute(ATTR_USER_ID)),
                        kv("outcome", request.getAttribute(ATTR_OUTCOME)));
            }
            MDC.remove(MDC_KEY);
        }
    }
}
