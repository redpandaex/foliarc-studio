package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Queue;

import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.entity.OutboxEventEntity;
import studio.foliarc.biz.outbox.mapper.OutboxEventMapper;
import studio.foliarc.biz.outbox.mapper.ProcessedEventMapper;
import studio.foliarc.biz.outbox.service.OutboxService;
import tools.jackson.databind.ObjectMapper;

class OutboxAndRagTests {

    @Test
    void rabbitmqQueuesDeclareDeadLetterExchange() {
        RabbitMqTopologyConfig cfg = new RabbitMqTopologyConfig();
        Queue ingest = cfg.ragIngestQueue();
        Queue chapterIngest = cfg.ragChapterIngestQueue();
        Queue delete = cfg.ragDeleteQueue();
        Queue memory = cfg.memoryExtractQueue();
        Queue summary = cfg.memorySummaryQueue();

        assertThat(ingest.getArguments()).containsEntry("x-dead-letter-exchange", RabbitMqTopologyConfig.RAG_DLX);
        assertThat(ingest.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitMqTopologyConfig.INGEST_DLQ);
        assertThat(chapterIngest.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitMqTopologyConfig.CHAPTER_INGEST_DLQ);
        assertThat(delete.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitMqTopologyConfig.DELETE_DLQ);
        assertThat(memory.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitMqTopologyConfig.MEMORY_DLQ);
        assertThat(summary.getArguments()).containsEntry("x-dead-letter-routing-key", RabbitMqTopologyConfig.SUMMARY_DLQ);
    }

    @Test
    void outboxServicePersistsPendingEventAndClaimsIdempotencyOnce() {
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        ProcessedEventMapper processedMapper = mock(ProcessedEventMapper.class);
        OutboxService service = new OutboxService(outboxMapper, processedMapper, new ObjectMapper());

        UUID docId = UUID.randomUUID();
        OutboxEventEntity ev = service.enqueue(
                "DOCUMENT",
                docId,
                "DOC_INGEST",
                RabbitMqTopologyConfig.RK_INGEST,
                "DOC_INGEST:" + docId + ":v1",
                Map.of("document_id", docId.toString(), "doc_version", 1)
        );

        ArgumentCaptor<OutboxEventEntity> captor = ArgumentCaptor.forClass(OutboxEventEntity.class);
        verify(outboxMapper).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(captor.getValue().getRoutingKey()).isEqualTo(RabbitMqTopologyConfig.RK_INGEST);
        assertThat(ev.getPayloadJson()).contains(docId.toString());

        when(processedMapper.tryClaim("k1", "worker")).thenReturn(1, 0);
        assertThat(service.claimConsumerIdempotency("k1", "worker")).isTrue();
        assertThat(service.claimConsumerIdempotency("k1", "worker")).isFalse();
    }
}
