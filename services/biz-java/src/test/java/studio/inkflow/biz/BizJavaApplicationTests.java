package studio.inkflow.biz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import studio.inkflow.biz.health.HealthController;

class BizJavaApplicationTests {

    @Test
    void healthEndpointReturnsOkPayload() {
        HealthController controller = new HealthController(RestClient.create());
        Map<String, Object> result = controller.health();

        assertThat(result.get("status")).isEqualTo("ok");
        assertThat(result.get("service")).isEqualTo("biz-java");
        assertThat(result.get("javaVersion")).asString().startsWith("21");
    }
}
