package studio.inkflow.biz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient agentRestClient(
            @Value("${inkflow.agent.base-url:http://localhost:8000}") String baseUrl,
            @Value("${inkflow.agent.internal-token}") String internalToken
    ) {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }

    @Bean
    public WebClient agentWebClient(
            @Value("${inkflow.agent.base-url:http://localhost:8000}") String baseUrl,
            @Value("${inkflow.agent.internal-token}") String internalToken
    ) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }
}
