package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;

import reactor.core.publisher.Flux;
import studio.foliarc.biz.run.service.RedisStreamSseBridge;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class RedisStreamSseBridgeTests {

    @SuppressWarnings("unchecked")
    @Test
    void streamsOrderedEventsAndCompletesOnTerminalEvent() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        UUID runId = UUID.randomUUID();
        String key = RedisStreamSseBridge.streamKey(runId);

        MapRecord<String, Object, Object> ev1 = MapRecord.create(key, Map.<Object, Object>of(
                "type", "text_delta",
                "seq", "2",
                "run_id", runId.toString(),
                "agent", "writer",
                "timestamp", "2026-11-23T08:00:00Z",
                "payload", "{\"content\":\"夜雨微凉\"}"
        )).withId(RecordId.of("1700000000000-2"));

        MapRecord<String, Object, Object> ev2 = MapRecord.create(key, Map.<Object, Object>of(
                "type", "done",
                "seq", "3",
                "run_id", runId.toString(),
                "agent", "writer",
                "timestamp", "2026-11-23T08:00:01Z",
                "payload", "{\"status\":\"completed\"}"
        )).withId(RecordId.of("1700000000000-3"));

        when(streamOps.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Flux.just(ev1))
                .thenReturn(Flux.just(ev2));

        ObjectMapper mapper = new ObjectMapper();
        RedisStreamSseBridge bridge = new RedisStreamSseBridge(redis, mapper);

        List<ServerSentEvent<String>> received = bridge.streamRun(runId, "1700000000000-1")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(received).hasSize(2);
        assertThat(received.get(0).id()).isEqualTo("1700000000000-2");
        assertThat(received.get(0).event()).isEqualTo("text_delta");

        JsonNode data0 = mapper.readTree(received.get(0).data());
        assertThat(data0.get("event_id").asString()).isEqualTo("1700000000000-2");
        assertThat(data0.get("seq").asInt()).isEqualTo(2);
        assertThat(data0.get("payload").get("content").asString()).isEqualTo("夜雨微凉");

        assertThat(received.get(1).id()).isEqualTo("1700000000000-3");
        assertThat(received.get(1).event()).isEqualTo("done");
    }

    @SuppressWarnings("unchecked")
    @Test
    void skipsHistoricalInterruptAndContinuesAfterResume() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        UUID runId = UUID.randomUUID();
        String key = RedisStreamSseBridge.streamKey(runId);

        MapRecord<String, Object, Object> ev1 = MapRecord.create(key, Map.<Object, Object>of(
                "type", "thought_delta",
                "seq", "1",
                "run_id", runId.toString(),
                "agent", "director",
                "timestamp", "2026-11-23T08:00:00Z",
                "payload", "{\"content\":\"大纲初稿\"}"
        )).withId(RecordId.of("1700000000000-1"));

        MapRecord<String, Object, Object> oldInterrupt = MapRecord.create(key, Map.<Object, Object>of(
                "type", "interrupt",
                "seq", "2",
                "run_id", runId.toString(),
                "agent", "director",
                "timestamp", "2026-11-23T08:00:01Z",
                "payload", "{\"interrupt_type\":\"OUTLINE_APPROVAL\"}"
        )).withId(RecordId.of("1700000000000-2"));

        MapRecord<String, Object, Object> resumedDelta = MapRecord.create(key, Map.<Object, Object>of(
                "type", "text_delta",
                "seq", "3",
                "run_id", runId.toString(),
                "agent", "writer",
                "timestamp", "2026-11-23T08:00:02Z",
                "payload", "{\"content\":\"审批后继续生成的正文\"}"
        )).withId(RecordId.of("1700000000000-3"));

        MapRecord<String, Object, Object> done = MapRecord.create(key, Map.<Object, Object>of(
                "type", "done",
                "seq", "4",
                "run_id", runId.toString(),
                "agent", "writer",
                "timestamp", "2026-11-23T08:00:03Z",
                "payload", "{\"status\":\"completed\"}"
        )).withId(RecordId.of("1700000000000-4"));

        when(streamOps.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Flux.just(ev1, oldInterrupt))
                .thenReturn(Flux.just(resumedDelta, done));

        ObjectMapper mapper = new ObjectMapper();
        RedisStreamSseBridge bridge = new RedisStreamSseBridge(redis, mapper);

        List<ServerSentEvent<String>> received = bridge.streamRun(runId, null, () -> "RUNNING")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(received).extracting(ServerSentEvent::event)
                .containsExactly("thought_delta", "text_delta", "done");
    }

    @SuppressWarnings("unchecked")
    @Test
    void stopsOnActiveInterruptWhenRunIsPaused() {
        ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
        ReactiveStreamOperations<String, Object, Object> streamOps = mock(ReactiveStreamOperations.class);
        when(redis.opsForStream()).thenReturn(streamOps);

        UUID runId = UUID.randomUUID();
        String key = RedisStreamSseBridge.streamKey(runId);

        MapRecord<String, Object, Object> activeInterrupt = MapRecord.create(key, Map.<Object, Object>of(
                "type", "interrupt",
                "seq", "2",
                "run_id", runId.toString(),
                "agent", "director",
                "timestamp", "2026-11-23T08:00:01Z",
                "payload", "{\"interrupt_type\":\"OUTLINE_APPROVAL\"}"
        )).withId(RecordId.of("1700000000000-2"));

        when(streamOps.read(any(StreamReadOptions.class), any(StreamOffset.class)))
                .thenReturn(Flux.just(activeInterrupt));

        RedisStreamSseBridge bridge = new RedisStreamSseBridge(redis, new ObjectMapper());
        List<ServerSentEvent<String>> received = bridge.streamRun(runId, null, () -> "PAUSED")
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(received).extracting(ServerSentEvent::event).containsExactly("interrupt");
    }
}
