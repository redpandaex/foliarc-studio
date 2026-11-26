package studio.foliarc.biz.common;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Emits a structured single-line HTTP access log entry per request with traceId, status,
 * latency, authenticated userId (if any), and sanitized client IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpAccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HttpAccessLogFilter.class);

    private final boolean enabled;

    public HttpAccessLogFilter() {
        this(true);
    }

    @Autowired
    public HttpAccessLogFilter(@Value("${foliarc.http-log.enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        long startNanos = System.nanoTime();
        boolean threw = false;
        try {
            chain.doFilter(request, response);
        } catch (Throwable ex) {
            threw = true;
            throw ex;
        } finally {
            long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
            int status = (threw && response.getStatus() < 400) ? 500 : response.getStatus();
            String traceId = resolveTraceId(request);
            String userId = request.getAttribute(TraceContext.USER_ID_ATTRIBUTE) instanceof String uid && !uid.isBlank()
                    ? TraceContext.sanitizeForLog(uid)
                    : "anonymous";
            String method = TraceContext.sanitizeForLog(request.getMethod());
            String path = TraceContext.sanitizeForLog(request.getRequestURI());
            String clientIp = TraceContext.resolveClientIp(request);
            boolean async = request.isAsyncStarted();

            if (isHealthPath(path)) {
                log.debug(
                        "HTTP_ACCESS traceId={} method={} path={} status={} durationMs={} userId={} clientIp={} async={}",
                        traceId, method, path, status, durationMs, userId, clientIp, async
                );
            } else if (status >= 500) {
                log.error(
                        "HTTP_ACCESS traceId={} method={} path={} status={} durationMs={} userId={} clientIp={} async={}",
                        traceId, method, path, status, durationMs, userId, clientIp, async
                );
            } else if (status >= 400) {
                log.warn(
                        "HTTP_ACCESS traceId={} method={} path={} status={} durationMs={} userId={} clientIp={} async={}",
                        traceId, method, path, status, durationMs, userId, clientIp, async
                );
            } else {
                log.info(
                        "HTTP_ACCESS traceId={} method={} path={} status={} durationMs={} userId={} clientIp={} async={}",
                        traceId, method, path, status, durationMs, userId, clientIp, async
                );
            }
        }
    }

    private static String resolveTraceId(HttpServletRequest request) {
        String traceId = TraceContext.currentTraceId();
        if (traceId != null && !traceId.isBlank()) {
            return TraceContext.sanitizeForLog(traceId);
        }
        Object attr = request.getAttribute(TraceContext.REQUEST_ATTRIBUTE);
        if (attr instanceof String raw && !raw.isBlank()) {
            return TraceContext.sanitizeForLog(raw);
        }
        return "-";
    }

    private static boolean isHealthPath(String path) {
        return "/api/v1/health".equals(path)
                || "/api/v1/admin/health".equals(path)
                || path.startsWith("/actuator/health");
    }
}
