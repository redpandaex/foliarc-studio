package studio.inkflow.biz.auth;

import java.util.UUID;

public record AuthenticatedUser(
        UUID userId,
        String email,
        String username,
        String role
) {
}
