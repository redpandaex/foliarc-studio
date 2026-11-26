package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Mono;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.TraceContext;
import studio.foliarc.biz.common.TraceIdFilter;
import studio.foliarc.biz.gateway.GatewayResilienceConfig;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.entity.AgentRunEntity;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.run.service.AgentRunService;
import studio.foliarc.biz.run.service.RedisStreamSseBridge;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AgentRunTraceTests {

    @Test
    void propagatesEachRunRequestTraceToAgentAndTheCancelledStreamEvent() throws Exception {
        UUID ownerId = UUID.randomUUID();
        var book = new BookEntity();
        book.setId(UUID.randomUUID());
        book.setTitle("Trace novel");
        var books = mock(BookService.class);
        when(books.requireOwnedBook(ownerId, book.getId())).thenReturn(book);
        var runs = mock(AgentRunMapper.class);
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> controls = mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        StreamOperations<String, Object, Object> stream = mock(StreamOperations.class);
        when(redis.opsForValue()).thenReturn(controls);
        when(redis.<Object, Object>opsForStream()).thenReturn(stream);
        when(stream.add(any(String.class), any())).thenReturn(RecordId.of("6-5"));

        var requests = new ArrayList<AgentRequest>();
        var json = new ObjectMapper();
        var agent = new GatewayResilienceConfig().agentWebClient("http://agent.example.test", "internal-test")
                .mutate().exchangeFunction(request -> {
                    var wire = new MockClientHttpRequest(request.method(), request.url());
                    return request.writeTo(wire, ExchangeStrategies.withDefaults())
                            .then(Mono.defer(wire::getBodyAsString)).map(body -> {
                                requests.add(new AgentRequest(request.url().getPath(),
                                        request.headers().getFirst(TraceContext.HEADER_NAME), json.readTree(body)));
                                return ClientResponse.create(HttpStatus.ACCEPTED).build();
                            });
                }).build();
        var service = new AgentRunService(runs, mock(AgentCheckpointMapper.class), books, mock(ChapterMapper.class),
                agent, redis, mock(RedisRateLimiterService.class), mock(BillingService.class),
                CircuitBreaker.ofDefaults("trace"), Bulkhead.ofDefaults("trace"));

        withTrace("start-6.5", () -> service.startRun(ownerId,
                new RunDtos.StartRunRequest(book.getId(), null, "AGENT", "CONTINUE", null, "", "", false)));
        var inserted = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(runs).insert(inserted.capture());
        var run = inserted.getValue();
        UUID runId = run.getId();
        when(runs.selectById(runId)).thenReturn(run);
        withTrace("pause-6.5", () -> service.pauseRun(ownerId, runId));

        run.setStatus("PAUSED");
        withTrace("resume-6.5", () -> service.resumeRun(ownerId, runId, new RunDtos.ResumeRunRequest("继续")));
        run.setStatus("PAUSED");
        withTrace("approve-6.5", () -> service.approveRun(ownerId, runId, new RunDtos.ApproveRunRequest(true, "", "")));
        run.setStatus("PAUSED");
        withTrace("cancel-6.5", () -> service.cancelRun(ownerId, runId));

        String runPath = "/internal/v1/runs/" + runId;
        assertThat(requests).extracting(AgentRequest::path).containsExactly(
                "/internal/v1/runs/start", runPath + "/control", runPath + "/resume", runPath + "/resume");
        assertThat(requests).extracting(AgentRequest::traceId).containsExactly("start-6.5", "pause-6.5", "resume-6.5", "approve-6.5");
        for (int i : new int[]{0, 2, 3}) {
            AgentRequest request = requests.get(i);
            assertThat(request.body().get("trace_id").asString()).isEqualTo(request.traceId());
        }
        assertThat(requests.get(3).body().get("approval_payload").get("approved").asBoolean()).isTrue();
        verify(stream).add(eq(RedisStreamSseBridge.streamKey(runId)),
                argThat(fields -> "cancel-6.5".equals(fields.get("trace_id")) && "done".equals(fields.get("type"))));
    }

    private static void withTrace(String traceId, Runnable action) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(TraceContext.HEADER_NAME, traceId);
        new TraceIdFilter().doFilter(request, new MockHttpServletResponse(), (req, res) -> action.run());
    }

    private record AgentRequest(String path, String traceId, JsonNode body) {}
}
