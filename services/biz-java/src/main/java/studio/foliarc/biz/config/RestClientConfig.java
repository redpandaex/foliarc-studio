package studio.foliarc.biz.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

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
                .build();
    }

    @Bean
    public WebClient agentWebClient(
            @Value("${foliarc.agent.base-url:http://localhost:8000}") String baseUrl,
            @Value("${foliarc.agent.internal-token}") String internalToken
    ) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken)
                .build();
    }
}
