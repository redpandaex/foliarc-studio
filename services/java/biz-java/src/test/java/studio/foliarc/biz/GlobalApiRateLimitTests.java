package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import studio.foliarc.biz.auth.AuthenticatedUser;
import studio.foliarc.biz.gateway.GlobalApiRateLimitFilter;
import studio.foliarc.biz.gateway.GlobalApiRateLimiter;

class GlobalApiRateLimitTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dualWindowLimiterEnforcesBurstAndRpmAndFailsOpenOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 0L, 59L, 239L, 0L))
                .thenReturn(List.of(0L, 1L, 0L, 180L, 7L))
                .thenReturn(List.of(0L, 2L, 40L, 0L, 42L))
                .thenThrow(new RuntimeException("redis connection refused"));

        var limiter = new GlobalApiRateLimiter(redis, 60, 240);

        var ok = limiter.check("user:1");
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.limit()).isEqualTo(240L);
        assertThat(ok.remaining()).isEqualTo(59L);

        var burstBlocked = limiter.check("user:1");
        assertThat(burstBlocked.allowed()).isFalse();
        assertThat(burstBlocked.errorCode()).isEqualTo("API_RATE_LIMIT_BURST");
        assertThat(burstBlocked.limit()).isEqualTo(60L);
        assertThat(burstBlocked.remaining()).isZero();
        assertThat(burstBlocked.retryAfterSeconds()).isEqualTo(7L);

        var rpmBlocked = limiter.check("user:1");
        assertThat(rpmBlocked.allowed()).isFalse();
        assertThat(rpmBlocked.errorCode()).isEqualTo("API_RATE_LIMIT_RPM");
        assertThat(rpmBlocked.limit()).isEqualTo(240L);
        assertThat(rpmBlocked.remaining()).isZero();
        assertThat(rpmBlocked.retryAfterSeconds()).isEqualTo(42L);

        var failOpen = limiter.check("user:1");
        assertThat(failOpen.allowed()).isTrue();
        assertThat(failOpen.remaining()).isEqualTo(240L);
    }

    @Test
    void filterKeysByAuthenticatedUserOrClientIpReturns429AndBypassesExemptPaths() throws Exception {
        var limiter = mock(GlobalApiRateLimiter.class);
        var filter = new GlobalApiRateLimitFilter(limiter, true);

        UUID userId = UUID.randomUUID();
        var principal = new AuthenticatedUser(userId, "author@foliarc.dev", "author", "AUTHOR");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_AUTHOR"))));
        when(limiter.check("user:" + userId))
                .thenReturn(new GlobalApiRateLimiter.Decision(true, "", "", 240L, 230L, 0L));

        var authReq = new MockHttpServletRequest("GET", "/api/v1/books/1/tree");
        var authRes = new MockHttpServletResponse();
        var okChain = new MockFilterChain();
        filter.doFilter(authReq, authRes, okChain);
        assertThat(authRes.getStatus()).isEqualTo(200);
        assertThat(authRes.getHeader("X-RateLimit-Limit")).isEqualTo("240");
        assertThat(authRes.getHeader("X-RateLimit-Remaining")).isEqualTo("230");
        assertThat(okChain.getRequest()).isNotNull();

        SecurityContextHolder.clearContext();
        when(limiter.check(eq("ip:198.51.100.25")))
                .thenReturn(new GlobalApiRateLimiter.Decision(
                        false, "API_RATE_LIMIT_BURST", "请求过于频繁", 60L, 0L, 8L));
        var anonReq = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        anonReq.addHeader("X-Forwarded-For", "198.51.100.25, 10.0.0.2");
        var anonRes = new MockHttpServletResponse();
        var blockedChain = new MockFilterChain();
        filter.doFilter(anonReq, anonRes, blockedChain);

        assertThat(anonRes.getStatus()).isEqualTo(429);
        assertThat(anonRes.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("8");
        assertThat(anonRes.getHeader("X-RateLimit-Limit")).isEqualTo("60");
        assertThat(anonRes.getHeader("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(anonRes.getContentAsString())
                .contains("\"code\":\"API_RATE_LIMIT_BURST\"")
                .contains("\"message\":\"请求过于频繁\"");
        assertThat(blockedChain.getRequest()).isNull();

        verify(limiter).check("ip:198.51.100.25");

        var bypassLimiter = mock(GlobalApiRateLimiter.class);
        var bypassFilter = new GlobalApiRateLimitFilter(bypassLimiter, true);
        for (String path : new String[]{"/api/v1/health", "/api/v1/health/agent", "/actuator/health", "/internal/v1/runs/1/events"}) {
            bypassFilter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), new MockFilterChain());
        }
        bypassFilter.doFilter(new MockHttpServletRequest("OPTIONS", "/api/v1/books"), new MockHttpServletResponse(), new MockFilterChain());
        verifyNoInteractions(bypassLimiter);
    }
}
