package studio.foliarc.admin.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminHealthController {

    private final int port;

    public AdminHealthController(@Value("${server.port:8081}") int port) {
        this.port = port;
    }

    @GetMapping("/api/v1/admin/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "admin-java", "version", "0.6.0", "port", port);
    }
}
