package studio.foliarc.biz.outbox;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
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
    public static final String CHAPTER_INGEST_QUEUE = "foliarc.rag.chapter.ingest.queue";
    public static final String DELETE_QUEUE = "foliarc.rag.delete.queue";
    public static final String MEMORY_QUEUE = "foliarc.memory.extract.queue";
    public static final String SUMMARY_QUEUE = "foliarc.memory.summary.queue";
    public static final String DISTILL_QUEUE = "foliarc.memory.distill.queue";
    public static final String ROLLUP_QUEUE = "foliarc.memory.rollup.queue";
    public static final String RIPPLE_ANALYZE_QUEUE = "foliarc.ripple.analyze.queue";
    public static final String RIPPLE_PATCH_QUEUE = "foliarc.ripple.patch.queue";

    public static final String INGEST_DLQ = "foliarc.rag.ingest.dlq";
    public static final String CHAPTER_INGEST_DLQ = "foliarc.rag.chapter.ingest.dlq";
    public static final String DELETE_DLQ = "foliarc.rag.delete.dlq";
    public static final String MEMORY_DLQ = "foliarc.memory.extract.dlq";
    public static final String SUMMARY_DLQ = "foliarc.memory.summary.dlq";
    public static final String DISTILL_DLQ = "foliarc.memory.distill.dlq";
    public static final String ROLLUP_DLQ = "foliarc.memory.rollup.dlq";
    public static final String RIPPLE_ANALYZE_DLQ = "foliarc.ripple.analyze.dlq";
    public static final String RIPPLE_PATCH_DLQ = "foliarc.ripple.patch.dlq";

    public static final String RK_INGEST = "rag.ingest";
    public static final String RK_CHAPTER_INGEST = "rag.chapter.ingest";
    public static final String RK_DELETE = "rag.delete";
    public static final String RK_MEMORY = "memory.extract";
    public static final String RK_SUMMARY = "memory.summary";
    public static final String RK_DISTILL = "memory.distill";
    public static final String RK_ROLLUP = "memory.rollup";
    public static final String RK_RIPPLE_ANALYZE = "ripple.analyze";
    public static final String RK_RIPPLE_PATCH = "ripple.patch";

    @Bean
    public TopicExchange ragExchange() {
        return new TopicExchange(RAG_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange ragDeadLetterExchange() {
        return new DirectExchange(RAG_DLX, true, false);
    }

    @Bean
    public Declarables rippleAnalyzeTopology(TopicExchange ragExchange, DirectExchange ragDeadLetterExchange) {
        return rippleTopology(RIPPLE_ANALYZE_QUEUE, RIPPLE_ANALYZE_DLQ, RK_RIPPLE_ANALYZE,
                ragExchange, ragDeadLetterExchange);
    }

    @Bean
    public Declarables ripplePatchTopology(TopicExchange ragExchange, DirectExchange ragDeadLetterExchange) {
        return rippleTopology(RIPPLE_PATCH_QUEUE, RIPPLE_PATCH_DLQ, RK_RIPPLE_PATCH,
                ragExchange, ragDeadLetterExchange);
    }

    private static Declarables rippleTopology(String queueName, String dlqName, String routingKey,
                                              TopicExchange exchange, DirectExchange deadLetterExchange) {
        Queue queue = QueueBuilder.durable(queueName)
                .deadLetterExchange(deadLetterExchange.getName()).deadLetterRoutingKey(dlqName).build();
        Queue dlq = QueueBuilder.durable(dlqName).build();
        return new Declarables(queue, dlq, BindingBuilder.bind(queue).to(exchange).with(routingKey),
                BindingBuilder.bind(dlq).to(deadLetterExchange).with(dlqName));
    }

    @Bean
    public Queue ragIngestQueue() {
        return QueueBuilder.durable(INGEST_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", INGEST_DLQ)
                .build();
    }

    @Bean
    public Queue ragChapterIngestQueue() {
        return QueueBuilder.durable(CHAPTER_INGEST_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", CHAPTER_INGEST_DLQ)
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
    public Queue memorySummaryQueue() {
        return QueueBuilder.durable(SUMMARY_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", SUMMARY_DLQ)
                .build();
    }

    @Bean
    public Queue memoryDistillQueue() {
        return QueueBuilder.durable(DISTILL_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", DISTILL_DLQ)
                .build();
    }

    @Bean
    public Queue memoryRollupQueue() {
        return QueueBuilder.durable(ROLLUP_QUEUE)
                .withArgument("x-dead-letter-exchange", RAG_DLX)
                .withArgument("x-dead-letter-routing-key", ROLLUP_DLQ)
                .build();
    }

    @Bean
    public Queue ragIngestDlq() {
        return QueueBuilder.durable(INGEST_DLQ).build();
    }

    @Bean
    public Queue ragChapterIngestDlq() {
        return QueueBuilder.durable(CHAPTER_INGEST_DLQ).build();
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
    public Queue memorySummaryDlq() {
        return QueueBuilder.durable(SUMMARY_DLQ).build();
    }

    @Bean
    public Queue memoryDistillDlq() {
        return QueueBuilder.durable(DISTILL_DLQ).build();
    }

    @Bean
    public Queue memoryRollupDlq() {
        return QueueBuilder.durable(ROLLUP_DLQ).build();
    }

    @Bean
    public Binding ragIngestBinding(Queue ragIngestQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(ragIngestQueue).to(ragExchange).with(RK_INGEST);
    }

    @Bean
    public Binding ragChapterIngestBinding(Queue ragChapterIngestQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(ragChapterIngestQueue).to(ragExchange).with(RK_CHAPTER_INGEST);
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
    public Binding memorySummaryBinding(Queue memorySummaryQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(memorySummaryQueue).to(ragExchange).with(RK_SUMMARY);
    }

    @Bean
    public Binding memoryDistillBinding(Queue memoryDistillQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(memoryDistillQueue).to(ragExchange).with(RK_DISTILL);
    }

    @Bean
    public Binding memoryRollupBinding(Queue memoryRollupQueue, TopicExchange ragExchange) {
        return BindingBuilder.bind(memoryRollupQueue).to(ragExchange).with(RK_ROLLUP);
    }

    @Bean
    public Binding ragIngestDlqBinding(Queue ragIngestDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(ragIngestDlq).to(ragDeadLetterExchange).with(INGEST_DLQ);
    }

    @Bean
    public Binding ragChapterIngestDlqBinding(Queue ragChapterIngestDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(ragChapterIngestDlq).to(ragDeadLetterExchange).with(CHAPTER_INGEST_DLQ);
    }

    @Bean
    public Binding ragDeleteDlqBinding(Queue ragDeleteDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(ragDeleteDlq).to(ragDeadLetterExchange).with(DELETE_DLQ);
    }

    @Bean
    public Binding memoryExtractDlqBinding(Queue memoryExtractDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(memoryExtractDlq).to(ragDeadLetterExchange).with(MEMORY_DLQ);
    }

    @Bean
    public Binding memorySummaryDlqBinding(Queue memorySummaryDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(memorySummaryDlq).to(ragDeadLetterExchange).with(SUMMARY_DLQ);
    }

    @Bean
    public Binding memoryDistillDlqBinding(Queue memoryDistillDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(memoryDistillDlq).to(ragDeadLetterExchange).with(DISTILL_DLQ);
    }

    @Bean
    public Binding memoryRollupDlqBinding(Queue memoryRollupDlq, DirectExchange ragDeadLetterExchange) {
        return BindingBuilder.bind(memoryRollupDlq).to(ragDeadLetterExchange).with(ROLLUP_DLQ);
    }
}
