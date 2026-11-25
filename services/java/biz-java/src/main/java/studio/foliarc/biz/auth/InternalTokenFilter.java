package studio.foliarc.biz.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class InternalTokenFilter extends OncePerRequestFilter {

    private final RequestMatcher paths;
    private final byte[] expectedToken;

    public InternalTokenFilter(RequestMatcher paths, String internalToken) {
        this.paths = paths;
        this.expectedToken = internalToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !paths.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = request.getHeader("X-Internal-Token");
        if (expectedToken.length > 0 && token != null
                && MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"INVALID_INTERNAL_TOKEN\",\"message\":\"非法内部服务凭证\"}");
    }
}
