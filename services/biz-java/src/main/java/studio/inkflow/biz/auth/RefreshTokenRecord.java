package studio.inkflow.biz.auth;

import java.util.UUID;

public record RefreshTokenRecord(
        UUID userId,
        String familyId,
        boolean rotated
) {
}
