package studio.foliarc.biz.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares FoliarcStudio Phase 3 RabbitMQ topology:
 * - Main Topic Exchange {@code foliarc.rag.exchange}
 * - Durable work queues for ingestion, dual-write deletion, and structured memory extraction
 * - Dedicated Dead-Letter Exchange {@code foliarc.rag.dlx} and DLQs for failed messages
 */
@Configuration
public class RabbitMqTopologyConfig {

    public static final String RAG_EXCHANGE = "foliarc.rag.exchange";
    public static final String RAG_DLX = "foliarc.rag.dlx";

    public static final String INGEST_QUEUE = "foliarc.rag.ingest.queue";
    public static final String DELETE_QUEUE = "foliarc.rag.delete.queue";
    public static final String MEMORY_QUEUE = "foliarc.memory.extract.queue";

    public static final String INGEST_DLQ = "foliarc.rag.ingest.dlq";
    public static final String DELETE_DLQ = "foliarc.rag.delete.dlq";
    public static final String MEMORY_DLQ = "foliarc.memory.extract.dlq";

    public static final String RK_INGEST = "rag.ingest";
    public static final String RK_DELETE = "rag.delete";
    public static final String RK_MEMORY = "memory.extract";

    @Bean
    public TopicExchange ragExchange() {
        return new TopicExchange(RAG_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ragDeadLetterExchange() {
        return new DirectExchange(RAG_DLX, true, false);
    }

    @Bean
    public Queue ragIngestQueue() {
        return QueueBuilder.durable(INGEST_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", INGEST_DLQ)
                .build();
    }

    @Bean
    public Queue ragDeleteQueue() {
        return QueueBuilder.durable(DELETE_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", DELETE_DLQ)
                .build();
    }

    @Bean
    public Queue memoryExtractQueue() {
        return QueueBuilder.durable(MEMORY_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", MEMORY_DLQ)
                .build();
    }

    @Bean
    public Queue ragIngestDlq() {
        return QueueBuilder.durable(INGEST_DLQ).build();
    }

    @Bean
    public Queue ragDeleteDlq() {
        return QueueBuilder.durable(DELETE_DLQ).build();
    }

    @Bean
    public Queue memoryExtractDlq() {
        return QueueBuilder.durable(MEMORY_DLQ).build();
    }

    @Bean
    public Binding ragIngestBinding(Queue ragIngestQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(ragIngestQueue).to(ragExchange).with(RK_INGEST);
    }

    @Bean
    public Binding ragDeleteBinding(Queue ragDeleteQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(ragDeleteQueue).to(ragExchange).with(RK_DELETE);
    }

    @Bean
    public Binding memoryExtractBinding(Queue memoryExtractQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(memoryExtractQueue).to(ragExchange).with(RK_MEMORY);
    }

    @Bean
    public Binding ragIngestDlqBinding(Queue ragIngestDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(ragIngestDlq).to(ragDeadLetterExchange).with(INGEST_DLQ);
    }

    @Bean
    public Binding ragDeleteDlqBinding(Queue ragDeleteDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(ragDeleteDlq).to(ragDeadLetterExchange).with(DELETE_DLQ);
    }

    @Bean
    public Binding memoryExtractDlqBinding(Queue memoryExtractDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(memoryExtractDlq).to(ragDeadLetterExchange).with(MEMORY_DLQ);
    }
}
