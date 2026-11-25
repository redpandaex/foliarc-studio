package studio.foliarc.biz.outbox.service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import studio.foliarc.biz.outbox.entity.OutboxEventEntity;
import studio.foliarc.biz.outbox.mapper.OutboxEventMapper;
import studio.foliarc.biz.outbox.mapper.ProcessedEventMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Transactional Outbox writer & consumer idempotency registry.
 * Must be invoked inside the same {@code @Transactional} boundary as domain mutations
 * so PostgreSQL business rows and Outbox events commit atomically.
 */
@Service
public class OutboxService {

    private final OutboxEventMapper outboxEventMapper;
    private final ProcessedEventMapper processedEventMapper;
    private final ObjectMapper objectMapper;

    public OutboxService(
            OutboxEventMapper outboxEventMapper,
            ProcessedEventMapper processedEventMapper,
            ObjectMapper objectMapper
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.processedEventMapper = processedEventMapper;
        this.objectMapper = objectMapper;
    }

    public OutboxEventEntity enqueue(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String idempotencyKey,
            Map<String, Object> payload
    ) {
        OutboxEventEntity event = new OutboxEventEntity();
        event.setId(UUID.randomUUID());
        event.setAggregateType(aggregateType);
        event.setAggregateId(aggregateId);
        event.setEventType(eventType);
        event.setRoutingKey(routingKey);
        event.setIdempotencyKey(idempotencyKey);
        event.setPayloadJson(objectMapper.writeValueAsString(payload));
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryAt(OffsetDateTime.now());
        outboxEventMapper.insert(event);
        return event;
    }

    @Transactional
    public boolean claimConsumerIdempotency(String idempotencyKey, String consumerName) {
        return processedEventMapper.tryClaim(idempotencyKey, consumerName) > 0;
    }
}
