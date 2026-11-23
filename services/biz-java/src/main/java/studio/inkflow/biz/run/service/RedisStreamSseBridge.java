package studio.inkflow.biz.run.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reactive WebFlux SSE bridge reading structured events from Redis Streams.
 *
 * <p>Uses non-blocking {@code XREAD COUNT} loops (never {@code XREAD BLOCK} on Lettuce's shared
 * multiplexed connection) to support {@code Last-Event-ID} resumable replay, burst draining,
 * periodic {@code ping} heartbeats, and automatic cleanup on client disconnect.
 */
@Service
public class RedisStreamSseBridge {

    private static final Set<String> TERMINAL_EVENTS = Set.of("done", "error", "interrupt");
    private static final Duration POLL_INTERVAL = Duration.ofMillis(80);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final int MAX_IDLE_POLLS = 1500; // ~120s idle safety bound

    private final ReactiveStringRedisTemplate reactiveRedis;
    private final ObjectMapper objectMapper;

    public RedisStreamSseBridge(
            ReactiveStringRedisTemplate reactiveRedis,
            ObjectMapper objectMapper
    ) {
        this.reactiveRedis = reactiveRedis;
        this.objectMapper = objectMapper;
    }

    public static String streamKey(UUID runId) {
        return "inkflow:run:" + runId + ":events";
    }

    public static String controlKey(UUID runId) {
        return "inkflow:run:" + runId + ":ctrl";
    }

    public Flux<ServerSentEvent<String>> streamRun(UUID runId, String lastEventId) {
        String key = streamKey(runId);
        String startCursor = (lastEventId != null && !lastEventId.isBlank()) ? lastEventId.trim() : "0-0";

        AtomicReference<String> cursorRef = new AtomicReference<>(startCursor);
        AtomicInteger idleTicks = new AtomicInteger(0);
        AtomicBoolean terminalReached = new AtomicBoolean(false);
        Sinks.One<Void> stopHeartbeats = Sinks.one();

        Mono<List<MapRecord<String, Object, Object>>> pollOnce = Mono.defer(() -> {
            if (terminalReached.get()) {
                return Mono.empty();
            }
            return reactiveRedis.opsForStream()
                    .read(
                            StreamReadOptions.empty().count(100),
                            StreamOffset.create(key, ReadOffset.from(cursorRef.get()))
                    )
                    .collectList()
                    .doOnNext(batch -> {
                        if (!batch.isEmpty()) {
                            cursorRef.set(batch.getLast().getId().getValue());
                            for (MapRecord<String, Object, Object> record : batch) {
                                Object rawType = record.getValue().get("type");
                                if (rawType != null && isTerminal(rawType.toString())) {
                                    terminalReached.set(true);
                                    break;
                                }
                            }
                        }
                    });
        });

        Flux<ServerSentEvent<String>> streamEvents = pollOnce
                .expand(batch -> {
                    if (terminalReached.get()) {
                        return Mono.empty();
                    }
                    if (!batch.isEmpty()) {
                        idleTicks.set(0);
                        return pollOnce;
                    }
                    if (idleTicks.incrementAndGet() >= MAX_IDLE_POLLS) {
                        return Mono.empty();
                    }
                    return Mono.delay(POLL_INTERVAL).then(pollOnce);
                })
                .flatMapIterable(batch -> batch)
                .map(this::toServerSentEvent)
                .takeUntil(sse -> isTerminal(sse.event()))
                .doFinally(signal -> {
                    terminalReached.set(true);
                    stopHeartbeats.tryEmitEmpty();
                });

        Flux<ServerSentEvent<String>> heartbeats = Flux.interval(HEARTBEAT_INTERVAL)
                .takeUntilOther(stopHeartbeats.asMono())
                .map(tick -> buildPingEvent(runId));

        return Flux.merge(streamEvents, heartbeats);
    }

    ServerSentEvent<String> toServerSentEvent(MapRecord<String, Object, Object> record) {
        String streamId = record.getId().getValue();
        Map<Object, Object> raw = record.getValue();

        String type = str(raw.get("type"), "message");
        int seq = parseInt(str(raw.get("seq"), "0"));
        String runId = str(raw.get("run_id"), "");
        String traceId = str(raw.get("trace_id"), "");
        String agent = str(raw.get("agent"), "writer");
        String timestamp = str(raw.get("timestamp"), Instant.now().toString());
        String payloadJson = str(raw.get("payload"), "{}");

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", streamId);
        envelope.put("run_id", runId);
        if (!traceId.isBlank()) {
            envelope.put("trace_id", traceId);
        }
        envelope.put("seq", seq);
        envelope.put("type", type);
        envelope.put("agent", agent);
        envelope.put("timestamp", timestamp);
        envelope.put("payload", parsePayload(payloadJson));

        return ServerSentEvent.<String>builder()
                .id(streamId)
                .event(type)
                .data(writeJson(envelope))
                .build();
    }

    private ServerSentEvent<String> buildPingEvent(UUID runId) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", "ping-" + Instant.now().toEpochMilli());
        envelope.put("run_id", runId.toString());
        envelope.put("seq", 0);
        envelope.put("type", "ping");
        envelope.put("timestamp", Instant.now().toString());
        envelope.put("payload", Map.of("ts", Instant.now().toEpochMilli()));

        return ServerSentEvent.<String>builder()
                .event("ping")
                .data(writeJson(envelope))
                .build();
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            return objectMapper.readTree(payloadJson);
        } catch (Exception ex) {
            return objectMapper.createObjectNode().put("raw", payloadJson);
        }
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    static boolean isTerminal(String eventType) {
        return eventType != null && TERMINAL_EVENTS.contains(eventType);
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
