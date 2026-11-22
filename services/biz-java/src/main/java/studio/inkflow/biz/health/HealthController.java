package studio.inkflow.biz.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final RestClient agentRestClient;

    public HealthController(RestClient agentRestClient) {
        this.agentRestClient = agentRestClient;
    }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("service", "biz-java");
        body.put("javaVersion", Runtime.version().toString());
        body.put("virtualThread", Thread.currentThread().isVirtual());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/agent")
    public ResponseEntity<Map<String, Object>> agentHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "biz-java");
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> upstream = agentRestClient.get()
                    .uri("/health")
                    .retrieve()
                    .body(Map.class);
            body.put("status", "ok");
            body.put("agent", upstream);
            return ResponseEntity.ok(body);
        } catch (Exception ex) {
            body.put("status", "degraded");
            body.put("agent", Map.of("status", "unreachable", "error", ex.getMessage()));
            return ResponseEntity.status(503).body(body);
        }
    }
}
