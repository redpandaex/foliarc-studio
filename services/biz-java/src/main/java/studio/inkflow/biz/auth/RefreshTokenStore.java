package studio.inkflow.biz.auth;

import java.time.Duration;
import java.util.Optional;

public interface RefreshTokenStore {

    void save(String tokenHash, RefreshTokenRecord record, Duration ttl);

    Optional<RefreshTokenRecord> find(String tokenHash);

    void markRotated(String tokenHash, RefreshTokenRecord record, Duration ttl);

    void revokeFamily(String familyId);
}
