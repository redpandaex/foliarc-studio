package studio.foliarc.biz.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;

class HttpAccessLogFilterTests {

    private Logger filterLogger;
    private Level originalLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        filterLogger = (Logger) LoggerFactory.getLogger(HttpAccessLogFilter.class);
        originalLevel = filterLogger.getLevel();
        filterLogger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        filterLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        filterLogger.detachAppender(appender);
        filterLogger.setLevel(originalLevel);
    }

    @Test
    void logsSuccessWithSanitizedIpTraceUserAndExcludesQueryString() throws Exception {
        var traceFilter = new TraceIdFilter();
        var accessFilter = new HttpAccessLogFilter(true);
        var request = new MockHttpServletRequest("GET", "/api/v1/books/123");
        request.setQueryString("token=secret-value");
        request.addHeader(TraceContext.HEADER_NAME, "trace-abc-123");
        request.addHeader("X-Forwarded-For", "203.0.113.9\r\ninjected, 10.0.0.1");
        request.setAttribute(TraceContext.USER_ID_ATTRIBUTE, "user-42");
        var response = new MockHttpServletResponse();

        traceFilter.doFilter(request, response, (req, res) -> accessFilter.doFilter(req, res, (r2, s2) -> {
            ((MockHttpServletResponse) s2).setStatus(200);
        }));

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.getFirst();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        String msg = event.getFormattedMessage();
        assertThat(msg)
                .contains("HTTP_ACCESS")
                .contains("traceId=trace-abc-123")
                .contains("method=GET")
                .contains("path=/api/v1/books/123")
                .contains("status=200")
                .contains("userId=user-42")
                .contains("clientIp=203.0.113.9_injected")
                .doesNotContain("secret-value")
                .doesNotContain("\n")
                .doesNotContain("\r");
    }

    @Test
    void logs4xxAsWarnAndExceptionsAsErrorWith500() throws Exception {
        var accessFilter = new HttpAccessLogFilter(true);

        var req429 = new MockHttpServletRequest("POST", "/api/v1/books");
        var res429 = new MockHttpServletResponse();
        accessFilter.doFilter(req429, res429, (req, res) -> ((MockHttpServletResponse) res).setStatus(429));

        var reqErr = new MockHttpServletRequest("GET", "/api/v1/boom");
        var resErr = new MockHttpServletResponse();
        assertThatThrownBy(() -> accessFilter.doFilter(reqErr, resErr, (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);

        assertThat(appender.list).hasSize(2);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("status=429").contains("userId=anonymous");
        assertThat(appender.list.get(1).getLevel()).isEqualTo(Level.ERROR);
        assertThat(appender.list.get(1).getFormattedMessage()).contains("status=500");
    }

    @Test
    void logsHealthEndpointsAtDebugAndSkipsAllWhenDisabled() throws Exception {
        var accessFilter = new HttpAccessLogFilter(true);
        for (String path : new String[]{"/api/v1/health", "/api/v1/admin/health", "/actuator/health/readiness"}) {
            accessFilter.doFilter(new MockHttpServletRequest("GET", path), new MockHttpServletResponse(), (r, s) -> {});
        }
        assertThat(appender.list).hasSize(3);
        assertThat(appender.list).allMatch(ev -> ev.getLevel() == Level.DEBUG);

        appender.list.clear();
        var disabledFilter = new HttpAccessLogFilter(false);
        disabledFilter.doFilter(new MockHttpServletRequest("GET", "/api/v1/books"), new MockHttpServletResponse(), (r, s) -> {});
        assertThat(appender.list).isEmpty();
    }
}
