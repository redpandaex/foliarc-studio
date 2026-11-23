package studio.inkflow.biz.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed Refresh Token store supporting rotation and token-family reuse detection.
 */
@Component
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String TOKEN_PREFIX = "inkflow:auth:rt:";
    private static final String FAMILY_PREFIX = "inkflow:auth:rtf:";

    private final StringRedisTemplate redis;

    public RedisRefreshTokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void save(String tokenHash, RefreshTokenRecord record, Duration ttl) {
        String tokenKey = TOKEN_PREFIX + tokenHash;
        String familyKey = FAMILY_PREFIX + record.familyId();
        redis.opsForValue().set(tokenKey, serialize(record), ttl);
        redis.opsForSet().add(familyKey, tokenHash);
        redis.expire(familyKey, ttl);
    }

    @Override
    public Optional<RefreshTokenRecord> find(String tokenHash) {
        String raw = redis.opsForValue().get(TOKEN_PREFIX + tokenHash);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String[] parts = raw.split("\\|");
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new RefreshTokenRecord(
                UUID.fromString(parts[0]),
                parts[1],
                "1".equals(parts[2])
        ));
    }

    @Override
    public void markRotated(String tokenHash, RefreshTokenRecord record, Duration ttl) {
        RefreshTokenRecord rotated = new RefreshTokenRecord(record.userId(), record.familyId(), true);
        redis.opsForValue().set(TOKEN_PREFIX + tokenHash, serialize(rotated), ttl);
    }

    @Override
    public void revokeFamily(String familyId) {
        String familyKey = FAMILY_PREFIX + familyId;
        Set<String> hashes = redis.opsForSet().members(familyKey);
        List<String> keysToDelete = new ArrayList<>();
        if (hashes != null) {
            for (String hash : hashes) {
                keysToDelete.add(TOKEN_PREFIX + hash);
            }
        }
        keysToDelete.add(familyKey);
        redis.delete(keysToDelete);
    }

    private static String serialize(RefreshTokenRecord record) {
        return record.userId() + "|" + record.familyId() + "|" + (record.rotated() ? "1" : "0");
    }
}
