package studio.foliarc.biz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import studio.foliarc.biz.common.TraceContext;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient agentRestClient(
            @Value("${foliarc.agent.base-url:http://localhost:8000}") String baseUrl,
            @Value("${foliarc.agent.internal-token}") String internalToken
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .requestInterceptor((request, body, execution) -> {
                    String traceId = TraceContext.currentTraceId();
                    if (traceId != null) {
                        request.getHeaders().set(TraceContext.HEADER_NAME, traceId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
