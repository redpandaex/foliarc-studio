package studio.foliarc.biz.gateway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.common.TraceContext;

/**
 * Security-chain filter enforcing dual-window global API rate limits keyed by authenticated
 * user ID or client IP, returning HTTP 429 JSON responses when triggered.
 */
public class GlobalApiRateLimitFilter extends OncePerRequestFilter {

    private final GlobalApiRateLimiter rateLimiter;
    private final boolean enabled;

    public GlobalApiRateLimitFilter(GlobalApiRateLimiter rateLimiter, boolean enabled) {
        this.rateLimiter = rateLimiter;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return path == null
                || "/api/v1/health".equals(path)
                || path.startsWith("/api/v1/health/")
                || "/actuator".equals(path)
                || path.startsWith("/actuator/")
                || path.startsWith("/internal/v1/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String subject = resolveSubject(request);
        GlobalApiRateLimiter.Decision decision = rateLimiter.check(subject);
        response.setHeader("X-RateLimit-Limit", String.valueOf(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));

        if (!decision.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, decision.retryAfterSeconds())));
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String body = "{\"code\":\"" + escapeJson(decision.errorCode())
                    + "\",\"message\":\"" + escapeJson(decision.message()) + "\"}";
            response.getWriter().write(body);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private static String resolveSubject(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof AuthenticatedUser user) {
            return "user:" + user.userId();
        }
        return "ip:" + TraceContext.resolveClientIp(request);
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
