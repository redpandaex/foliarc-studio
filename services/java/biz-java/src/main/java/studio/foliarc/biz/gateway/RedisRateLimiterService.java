package studio.foliarc.biz.gateway;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import studio.foliarc.biz.common.BizException;

/**
 * Atomic Redis + Lua dual-dimension RPM (Requests Per Minute) and TPM (Tokens Per Minute)
 * rate limiter protecting the AI Gateway against burst traffic and token floods.
 */
@Service
public class RedisRateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterService.class);

    private static final String DUAL_RPM_TPM_LUA = """
            local rpm_key = KEYS[1]
            local tpm_key = KEYS[2]
            local max_rpm = tonumber(ARGV[1])
            local max_tpm = tonumber(ARGV[2])
            local est_tokens = tonumber(ARGV[3])
            local ttl_sec = tonumber(ARGV[4])

            local cur_rpm = tonumber(redis.call('GET', rpm_key) or '0')
            local cur_tpm = tonumber(redis.call('GET', tpm_key) or '0')

            if cur_rpm + 1 > max_rpm then
                return {0, 1, max_rpm - cur_rpm, max_tpm - cur_tpm}
            end
            if cur_tpm + est_tokens > max_tpm then
                return {0, 2, max_rpm - cur_rpm, max_tpm - cur_tpm}
            end

            local next_rpm = redis.call('INCR', rpm_key)
            if next_rpm == 1 then
                redis.call('EXPIRE', rpm_key, ttl_sec)
            end
            local next_tpm = redis.call('INCRBY', tpm_key, est_tokens)
            if next_tpm == est_tokens then
                redis.call('EXPIRE', tpm_key, ttl_sec)
            end
            return {1, 0, max_rpm - next_rpm, max_tpm - next_tpm}
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;
    private final long rpmLimit;
    private final long tpmLimit;

    public RedisRateLimiterService(
            StringRedisTemplate redis,
            @Value("${foliarc.gateway.rpm-limit:20}") long rpmLimit,
            @Value("${foliarc.gateway.tpm-limit:120000}") long tpmLimit
    ) {
        this.redis = redis;
        this.rpmLimit = rpmLimit;
        this.tpmLimit = tpmLimit;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(DUAL_RPM_TPM_LUA);
        this.script.setResultType(List.class);
    }

    public long getRpmLimit() {
        return rpmLimit;
    }

    public long getTpmLimit() {
        return tpmLimit;
    }

    public RateLimitResult acquireOrThrow(UUID userId, long estimatedTokens) {
        return acquireOrThrow(userId, estimatedTokens, rpmLimit, tpmLimit);
    }

    @SuppressWarnings("unchecked")
    public RateLimitResult acquireOrThrow(UUID userId, long estimatedTokens, long customRpm, long customTpm) {
        long minuteBucket = Instant.now().getEpochSecond() / 60;
        String rpmKey = "foliarc:rl:rpm:" + userId + ":" + minuteBucket;
        String tpmKey = "foliarc:rl:tpm:" + userId + ":" + minuteBucket;
        long tokens = Math.max(100L, estimatedTokens);

        List<Long> res;
        try {
            res = (List<Long>) redis.execute(
                    script,
                    List.of(rpmKey, tpmKey),
                    String.valueOf(customRpm),
                    String.valueOf(customTpm),
                    String.valueOf(tokens),
                    "65"
            );
        } catch (Exception ex) {
            log.warn("Redis rate limiter unavailable, failing open cleanly: {}", ex.getMessage());
            return new RateLimitResult(true, customRpm, customTpm);
        }

        if (res == null || res.size() < 4) {
            return new RateLimitResult(true, customRpm, customTpm);
        }
        long allowed = ((Number) res.get(0)).longValue();
        long blockedCode = ((Number) res.get(1)).longValue();
        long remRpm = Math.max(0L, ((Number) res.get(2)).longValue());
        long remTpm = Math.max(0L, ((Number) res.get(3)).longValue());

        if (allowed == 0L) {
            if (blockedCode == 1L) {
                throw BizException.tooManyRequests(
                        "RATE_LIMIT_RPM",
                        "触发网关 RPM 限流（上限 " + customRpm + " 次/分钟），请稍后再试"
                );
            }
            throw BizException.tooManyRequests(
                    "RATE_LIMIT_TPM",
                    "触发网关 TPM Token 限流（上限 " + customTpm + " Tokens/分钟，剩余 " + remTpm + "），请稍后再试"
            );
        }
        return new RateLimitResult(true, remRpm, remTpm);
    }

    public void recordAdditionalTokens(UUID userId, long extraTokens) {
        if (extraTokens <= 0) {
            return;
        }
        try {
            long minuteBucket = Instant.now().getEpochSecond() / 60;
            String tpmKey = "foliarc:rl:tpm:" + userId + ":" + minuteBucket;
            Long total = redis.opsForValue().increment(tpmKey, extraTokens);
            if (total != null && total == extraTokens) {
                redis.expire(tpmKey, java.time.Duration.ofSeconds(65));
            }
        } catch (Exception ignored) {
            // Non-blocking accounting
        }
    }

    public record RateLimitResult(boolean allowed, long remainingRpm, long remainingTpm) {}
}
