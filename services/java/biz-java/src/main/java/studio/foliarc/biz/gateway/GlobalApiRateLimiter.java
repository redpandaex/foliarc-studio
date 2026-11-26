package studio.foliarc.biz.gateway;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/**
 * Dual-window (10s burst + 60s sustained) Redis + Lua API rate limiter protecting all
 * business endpoints from frontend infinite loops and abusive bursts.
 */
@Service
public class GlobalApiRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(GlobalApiRateLimiter.class);

    private static final String DUAL_WINDOW_LUA = """
            local burst_key = KEYS[1]
            local rpm_key = KEYS[2]
            local max_burst = tonumber(ARGV[1])
            local max_rpm = tonumber(ARGV[2])
            local burst_ttl = tonumber(ARGV[3])
            local rpm_ttl = tonumber(ARGV[4])
            local burst_retry = tonumber(ARGV[5])
            local rpm_retry = tonumber(ARGV[6])

            local cur_burst = tonumber(redis.call('GET', burst_key) or '0')
            local cur_rpm = tonumber(redis.call('GET', rpm_key) or '0')

            if cur_burst + 1 > max_burst then
                return {0, 1, 0, math.max(0, max_rpm - cur_rpm), burst_retry}
            end
            if cur_rpm + 1 > max_rpm then
                return {0, 2, math.max(0, max_burst - cur_burst), 0, rpm_retry}
            end

            local next_burst = redis.call('INCR', burst_key)
            if next_burst == 1 then
                redis.call('EXPIRE', burst_key, burst_ttl)
            end
            local next_rpm = redis.call('INCR', rpm_key)
            if next_rpm == 1 then
                redis.call('EXPIRE', rpm_key, rpm_ttl)
            end
            return {1, 0, math.max(0, max_burst - next_burst), math.max(0, max_rpm - next_rpm), 0}
            """;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> script;
    private final long burstPer10s;
    private final long rpmLimit;

    public GlobalApiRateLimiter(
            StringRedisTemplate redis,
            @Value("${foliarc.gateway.global-rate-limit.burst-per-10s:60}") long burstPer10s,
            @Value("${foliarc.gateway.global-rate-limit.rpm-limit:240}") long rpmLimit
    ) {
        this.redis = redis;
        this.burstPer10s = Math.max(1L, burstPer10s);
        this.rpmLimit = Math.max(1L, rpmLimit);
        this.script = new DefaultRedisScript<>();
        this.script.setScriptText(DUAL_WINDOW_LUA);
        this.script.setResultType(List.class);
    }

    public long getBurstPer10s() {
        return burstPer10s;
    }

    public long getRpmLimit() {
        return rpmLimit;
    }

    @SuppressWarnings("unchecked")
    public Decision check(String subjectKey) {
        long nowEpoch = Instant.now().getEpochSecond();
        long burstBucket = nowEpoch / 10L;
        long rpmBucket = nowEpoch / 60L;
        long burstRetry = Math.max(1L, 10L - (nowEpoch % 10L));
        long rpmRetry = Math.max(1L, 60L - (nowEpoch % 60L));

        String burstKey = "foliarc:rl:api:10s:" + subjectKey + ":" + burstBucket;
        String rpmKey = "foliarc:rl:api:60s:" + subjectKey + ":" + rpmBucket;

        List<Long> res;
        try {
            res = (List<Long>) redis.execute(
                    script,
                    List.of(burstKey, rpmKey),
                    String.valueOf(burstPer10s),
                    String.valueOf(rpmLimit),
                    "12",
                    "65",
                    String.valueOf(burstRetry),
                    String.valueOf(rpmRetry)
            );
        } catch (Exception ex) {
            log.warn("Global API rate limiter Redis unavailable, failing open cleanly: {}", ex.getMessage());
            return new Decision(true, "", "", rpmLimit, rpmLimit, 0L);
        }

        if (res == null || res.size() < 5) {
            return new Decision(true, "", "", rpmLimit, rpmLimit, 0L);
        }

        long allowed = ((Number) res.get(0)).longValue();
        long blockedCode = ((Number) res.get(1)).longValue();
        long remBurst = Math.max(0L, ((Number) res.get(2)).longValue());
        long remRpm = Math.max(0L, ((Number) res.get(3)).longValue());
        long retryAfter = Math.max(1L, ((Number) res.get(4)).longValue());

        if (allowed == 0L) {
            if (blockedCode == 1L) {
                return new Decision(
                        false,
                        "API_RATE_LIMIT_BURST",
                        "请求过于频繁（10 秒突发上限 " + burstPer10s + " 次），请稍后再试",
                        burstPer10s,
                        0L,
                        retryAfter
                );
            }
            return new Decision(
                    false,
                    "API_RATE_LIMIT_RPM",
                    "请求过于频繁（每分钟上限 " + rpmLimit + " 次），请稍后再试",
                    rpmLimit,
                    0L,
                    retryAfter
            );
        }

        return new Decision(true, "", "", rpmLimit, Math.min(remBurst, remRpm), 0L);
    }

    public record Decision(
            boolean allowed,
            String errorCode,
            String message,
            long limit,
            long remaining,
            long retryAfterSeconds
    ) {}
}
