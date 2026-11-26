package studio.foliarc.biz.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import studio.foliarc.biz.config.RestClientConfig;

class TraceIdFilterTests {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";
    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void prefersSafeIncomingTraceIdAndEchoesItOnAnErrorResponse() throws Exception {
        var request = new MockHttpServletRequest();
        String incoming = "Trace_2026.11-26" + "a".repeat(48);
        request.addHeader(TraceContext.HEADER_NAME, incoming);
        request.addHeader("traceparent", TRACEPARENT);
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            assertThat(TraceContext.currentTraceId()).isEqualTo(incoming);
            assertThat(req.getAttribute(TraceContext.REQUEST_ATTRIBUTE)).isEqualTo(incoming);
            response.setStatus(403);
        });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo(incoming);
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void parsesTraceparentWhenCustomHeaderIsUnsafe() throws Exception {
        for (String invalid : List.of(" ", "not safe", "a".repeat(65), "id:other")) {
            var request = new MockHttpServletRequest();
            request.addHeader(TraceContext.HEADER_NAME, invalid);
            request.addHeader("traceparent", TRACEPARENT);
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> assertThat(TraceContext.currentTraceId()).isEqualTo(TRACE_ID));

            assertThat(response.getHeader(TraceContext.HEADER_NAME)).isEqualTo(TRACE_ID);
            assertThat(TraceContext.currentTraceId()).isNull();
        }
    }

    @Test
    void generatesTraceWhenMissingAndKeepsItAcrossAsyncDispatch() throws Exception {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> assertThat(TraceContext.currentTraceId()).matches("[0-9a-f]{32}"));
        String generated = response.getHeader(TraceContext.HEADER_NAME);

        assertThat(generated).matches("[0-9a-f]{32}");
        assertThat(TraceContext.currentTraceId()).isNull();

        request.setDispatcherType(DispatcherType.ASYNC);
        var asyncResponse = new MockHttpServletResponse();
        filter.doFilter(request, asyncResponse, (req, res) -> assertThat(TraceContext.currentTraceId()).isEqualTo(generated));
        assertThat(asyncResponse.getHeader(TraceContext.HEADER_NAME)).isEqualTo(generated);
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void rejectsInvalidTraceparentAndRemovesOnlyTraceMdcAfterAnException() {
        var request = new MockHttpServletRequest();
        request.addHeader("traceparent", "00-" + TRACE_ID + "-" + "0".repeat(16) + "-01");
        var response = new MockHttpServletResponse();
        MDC.put("unrelated", "keep");
        try {
            assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                assertThat(TraceContext.currentTraceId()).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
                throw new ServletException("failed downstream");
            })).isInstanceOf(ServletException.class);

            assertThat(response.getHeader(TraceContext.HEADER_NAME)).matches("[0-9a-f]{32}").isNotEqualTo(TRACE_ID);
            assertThat(TraceContext.currentTraceId()).isNull();
            assertThat(MDC.get("unrelated")).isEqualTo("keep");
        } finally {
            MDC.remove("unrelated");
        }
    }

    @Test
    void restClientForwardsTheCurrentTraceOnlyWithinTheRequest() throws Exception {
        var builder = new RestClientConfig().agentRestClient("http://agent.example.test", "internal-test").mutate();
        var agent = MockRestServiceServer.bindTo(builder).build();
        var client = builder.build();
        agent.expect(requestTo("http://agent.example.test/health"))
                .andExpect(header(TraceContext.HEADER_NAME, TRACE_ID))
                .andExpect(header("X-Internal-Token", "internal-test"))
                .andRespond(withSuccess());
        agent.expect(requestTo("http://agent.example.test/health"))
                .andExpect(headerDoesNotExist(TraceContext.HEADER_NAME))
                .andRespond(withSuccess());

        var request = new MockHttpServletRequest();
        request.addHeader("traceparent", TRACEPARENT);
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> client.get().uri("/health").retrieve().toBodilessEntity());
        client.get().uri("/health").retrieve().toBodilessEntity();

        agent.verify();
    }
}
