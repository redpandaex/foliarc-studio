package studio.foliarc.biz.outbox.service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.entity.OutboxEventEntity;
import studio.foliarc.biz.outbox.mapper.OutboxEventMapper;

/**
 * Relays committed {@code outbox_events} rows to RabbitMQ with Publisher Confirms
 * and exponential backoff retry. Uses {@code FOR UPDATE SKIP LOCKED} so multiple
 * Java replicas can run concurrently without double-publishing the same event.
 */
@Component
public class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

    private final OutboxEventMapper outboxEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final TransactionTemplate transactionTemplate;
    private final boolean enabled;
    private final int maxRetries;

    public OutboxRelayScheduler(
            OutboxEventMapper outboxEventMapper,
            RabbitTemplate rabbitTemplate,
            TransactionTemplate transactionTemplate,
            @Value("${foliarc.outbox.enabled:true}") boolean enabled,
            @Value("${foliarc.outbox.max-retries:6}") int maxRetries
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${foliarc.outbox.poll-interval-ms:1000}")
    public void relayPendingEvents() {
        if (!enabled) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<OutboxEventEntity> batch = outboxEventMapper.lockDueEvents(20);
                for (OutboxEventEntity event : batch) {
                    dispatchOne(event);
                }
            });
        } catch (Exception ex) {
            log.debug("Outbox relay tick skipped or deferred: {}", ex.getMessage());
        }
    }

    private void dispatchOne(OutboxEventEntity event) {
        try {
            CorrelationData correlation = new CorrelationData(event.getId().toString());
            Message message = MessageBuilder
                    .withBody(event.getPayloadJson().getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                    .setContentEncoding(StandardCharsets.UTF_8.name())
                    .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                    .setMessageId(event.getId().toString())
                    .setHeader("x-event-type", event.getEventType())
                    .setHeader("x-idempotency-key", event.getIdempotencyKey())
                    .build();

            rabbitTemplate.send(
                    RabbitMqTopologyConfig.RAG_EXCHANGE,
                    event.getRoutingKey(),
                    message,
                    correlation
            );

            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (confirm != null && confirm.ack()) {
                event.setStatus("PUBLISHED");
                event.setPublishedAt(OffsetDateTime.now());
                event.setLastError(null);
                outboxEventMapper.updateById(event);
                log.info("Relayed outbox event {} ({} -> {})", event.getId(), event.getEventType(), event.getRoutingKey());
            } else {
                String reason = confirm != null ? confirm.reason() : "No broker confirm";
                markRetryOrFailed(event, "RabbitMQ NACK: " + reason);
            }
        } catch (Exception ex) {
            markRetryOrFailed(event, ex.getMessage());
        }
    }

    private void markRetryOrFailed(OutboxEventEntity event, String error) {
        int nextRetry = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        event.setRetryCount(nextRetry);
        event.setLastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error);
        if (nextRetry >= maxRetries) {
            event.setStatus("FAILED");
            log.error("Outbox event {} exhausted retries ({}): {}", event.getId(), nextRetry, error);
        } else {
            long delaySec = Math.min(60L, 1L << Math.min(nextRetry, 6));
            event.setNextRetryAt(OffsetDateTime.now().plusSeconds(delaySec));
            log.warn("Outbox event {} publish failed (attempt {}/{}), retry in {}s: {}",
                    event.getId(), nextRetry, maxRetries, delaySec, error);
        }
        outboxEventMapper.updateById(event);
    }
}
