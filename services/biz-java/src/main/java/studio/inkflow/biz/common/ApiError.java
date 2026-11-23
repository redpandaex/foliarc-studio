package studio.inkflow.biz.common;

import java.time.Instant;
import java.util.Map;

public record ApiError(
        String code,
        String message,
        Instant timestamp,
        Map<String, String> details
) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now(), Map.of());
    }

    public static ApiError of(String code, String message, Map<String, String> details) {
        return new ApiError(code, message, Instant.now(), details);
    }
}
