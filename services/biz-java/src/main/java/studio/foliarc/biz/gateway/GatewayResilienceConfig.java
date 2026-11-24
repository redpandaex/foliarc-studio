package studio.foliarc.biz.gateway;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Configures Resilience4j CircuitBreaker and Bulkhead isolation for outbound
 * calls from the Java gateway to the Python Agent engine so slow/failing LLM
 * executions never exhaust business API threads.
 */
@Configuration
public class GatewayResilienceConfig {

    @Bean
    public CircuitBreaker agentEngineCircuitBreaker() {
        CircuitBreakerConfig cfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(15))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();
        return CircuitBreakerRegistry.of(cfg).circuitBreaker("agentEngine");
    }

    @Bean
    public Bulkhead agentDispatchBulkhead(
            @Value("${foliarc.gateway.bulkhead-max-concurrent:20}") int maxConcurrent
    ) {
        BulkheadConfig cfg = BulkheadConfig.custom()
                .maxConcurrentCalls(Math.max(1, maxConcurrent))
                .maxWaitDuration(Duration.ofMillis(500))
                .build();
        return BulkheadRegistry.of(cfg).bulkhead("agentDispatch");
    }
}
