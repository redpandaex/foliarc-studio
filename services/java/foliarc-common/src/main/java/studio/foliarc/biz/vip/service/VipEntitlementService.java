package studio.foliarc.biz.vip.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.entity.UserSubscriptionEntity;
import studio.foliarc.biz.vip.entity.VipPlanEntity;
import studio.foliarc.biz.vip.mapper.UserSubscriptionMapper;
import studio.foliarc.biz.vip.mapper.VipPlanMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * Unified VIP entitlement resolver shared across Gateway, Billing, Knowledge Library,
 * and Phase 5.8 engines. Uses a 5-minute Redis short cache and automatically downgrades
 * expired subscriptions to {@code FREE} on read without relying on cron jobs.
 */
@Service
public class VipEntitlementService {

    private static final Logger log = LoggerFactory.getLogger(VipEntitlementService.class);

    public static final String PLAN_FREE = "FREE";
    public static final String PLAN_VIP = "VIP";
    public static final String PLAN_SVIP = "SVIP";
    public static final String PLAN_STUDIO = "STUDIO";

    public static final String REDIS_USER_KEY_PREFIX = "foliarc:vip:user:";
    public static final String REDIS_PLANS_VERSION_KEY = "foliarc:vip:plans:version";
    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(5);

    private static final List<String> TIER_ORDER = List.of(PLAN_FREE, PLAN_VIP, PLAN_SVIP, PLAN_STUDIO);

    private final VipPlanMapper planMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public VipEntitlementService(
            VipPlanMapper planMapper,
            UserSubscriptionMapper subscriptionMapper,
            StringRedisTemplate redis,
            ObjectMapper objectMapper
    ) {
        this.planMapper = planMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public VipDtos.ResolvedUserEntitlements resolveForUser(UUID userId) {
        if (userId == null) {
            return defaultResolvedFree();
        }

        String cacheKey = userCacheKey(userId);
        VipDtos.ResolvedUserEntitlements cached = readCachedUser(cacheKey);
        if (cached != null) {
            if (isExpired(cached.expiresAt())) {
                evictUserCache(userId);
            } else {
                return cached;
            }
        }

        UserSubscriptionEntity sub = subscriptionMapper.selectOne(
                new LambdaQueryWrapper<UserSubscriptionEntity>()
                        .eq(UserSubscriptionEntity::getUserId, userId)
                        .last("LIMIT 1")
        );

        String effectivePlanCode = PLAN_FREE;
        String effectiveStatus = "FREE_DEFAULT";
        OffsetDateTime startedAt = null;
        OffsetDateTime expiresAt = null;

        if (sub != null) {
            boolean activeStatus = "ACTIVE".equalsIgnoreCase(sub.getStatus());
            boolean expired = isExpired(sub.getExpiresAt());
            if (activeStatus && !expired && sub.getPlanCode() != null && !sub.getPlanCode().isBlank()) {
                effectivePlanCode = normalizePlanCode(sub.getPlanCode());
                effectiveStatus = "ACTIVE";
                startedAt = sub.getStartedAt();
                expiresAt = sub.getExpiresAt();
            } else if (expired) {
                effectiveStatus = "EXPIRED";
                startedAt = sub.getStartedAt();
                expiresAt = sub.getExpiresAt();
            }
        }

        VipPlanEntity plan = loadPlanEntity(effectivePlanCode);
        VipDtos.VipEntitlements entitlements = parseEntitlements(effectivePlanCode, plan.getEntitlementsJson());
        VipDtos.ResolvedUserEntitlements resolved = new VipDtos.ResolvedUserEntitlements(
                plan.getPlanCode(),
                plan.getDisplayName(),
                effectiveStatus,
                startedAt,
                expiresAt,
                plan.getCreditDiscountBps() != null ? plan.getCreditDiscountBps() : 10000,
                plan.getMonthlyGrantMicros() != null ? plan.getMonthlyGrantMicros() : 20_000_000L,
                entitlements
        );

        writeCachedUser(cacheKey, resolved);
        return resolved;
    }

    public VipPlanEntity loadPlanEntity(String rawPlanCode) {
        String code = normalizePlanCode(rawPlanCode);
        VipPlanEntity dbPlan = planMapper.selectById(code);
        if (dbPlan != null) {
            return dbPlan;
        }
        return builtInPlanFallback(code);
    }

    public List<VipPlanEntity> listAllPlans() {
        List<VipPlanEntity> dbPlans = planMapper.selectList(
                new LambdaQueryWrapper<VipPlanEntity>()
                        .eq(VipPlanEntity::getEnabled, true)
                        .orderByAsc(VipPlanEntity::getSortOrder)
        );
        if (dbPlans != null && !dbPlans.isEmpty()) {
            return dbPlans;
        }
        return TIER_ORDER.stream().map(this::builtInPlanFallback).toList();
    }

    public VipDtos.VipEntitlements parseEntitlements(String planCode, String json) {
        if (json != null && !json.isBlank()) {
            try {
                VipDtos.VipEntitlements parsed = objectMapper.readValue(json, VipDtos.VipEntitlements.class);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception ex) {
                log.warn("Failed to parse entitlements_json for plan {}, using defaults: {}", planCode, ex.getMessage());
            }
        }
        return defaultEntitlements(planCode);
    }

    public String serializeEntitlements(VipDtos.VipEntitlements entitlements) {
        try {
            return objectMapper.writeValueAsString(entitlements);
        } catch (Exception ex) {
            throw BizException.badRequest("INVALID_ENTITLEMENTS", "会员权益 JSON 序列化失败: " + ex.getMessage());
        }
    }

    public void evictUserCache(UUID userId) {
        if (userId == null) {
            return;
        }
        try {
            redis.delete(userCacheKey(userId));
        } catch (Exception ex) {
            log.debug("Unable to evict VIP user cache for {}: {}", userId, ex.getMessage());
        }
    }

    /**
     * Bumps the global plans cache version so any user-level cached entitlements
     * immediately refresh against newly hot-updated plan thresholds.
     */
    public void evictAllPlanCaches() {
        try {
            redis.opsForValue().increment(REDIS_PLANS_VERSION_KEY);
        } catch (Exception ex) {
            log.debug("Unable to bump VIP plans cache version: {}", ex.getMessage());
        }
    }

    public int getRippleScansUsedToday(UUID userId) {
        try {
            String raw = redis.opsForValue().get(rippleDailyKey(userId));
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (Exception ex) {
            return 0;
        }
    }

    public int incrementRippleScanToday(UUID userId) {
        try {
            String key = rippleDailyKey(userId);
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofHours(26));
            }
            return count == null ? 1 : count.intValue();
        } catch (Exception ex) {
            return 1;
        }
    }

    public BizException quotaExceeded(
            VipDtos.ResolvedUserEntitlements current,
            String feature,
            long currentLimit,
            long requested,
            String humanMessage
    ) {
        String recommendedPlan = nextRecommendedPlan(current.planCode());
        VipPlanEntity nextPlan = loadPlanEntity(recommendedPlan);
        VipDtos.VipEntitlements nextEnt = parseEntitlements(nextPlan.getPlanCode(), nextPlan.getEntitlementsJson());
        long recommendedLimit = switch (feature) {
            case "LIBRARY_DOCS" -> nextEnt.userLibraryMaxDocs();
            case "LIBRARY_STORAGE_MB" -> nextEnt.userLibraryMaxTotalBytes() / (1024L * 1024L);
            case "RIPPLE_RANGE" -> nextEnt.rippleMaxRangeChapters();
            case "RIPPLE_PATCH_CHAPTERS" -> nextEnt.rippleMaxPatchChapters();
            case "RIPPLE_DAILY_SCANS" -> nextEnt.rippleDailyScanLimit();
            case "SECTION_CHARS" -> nextEnt.maxSectionChars();
            default -> currentLimit;
        };

        Map<String, String> details = new LinkedHashMap<>();
        details.put("feature", feature);
        details.put("currentPlan", current.planCode());
        details.put("currentLimit", String.valueOf(currentLimit));
        details.put("requested", String.valueOf(requested));
        details.put("recommendedPlan", nextPlan.getPlanCode());
        details.put("recommendedPlanName", nextPlan.getDisplayName());
        details.put("recommendedLimit", String.valueOf(recommendedLimit));
        return BizException.vipQuotaExceeded(humanMessage, details);
    }

    public static String normalizePlanCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return PLAN_FREE;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isExpired(OffsetDateTime expiresAt) {
        return expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }

    public static String nextRecommendedPlan(String currentPlanCode) {
        String normalized = normalizePlanCode(currentPlanCode);
        int idx = TIER_ORDER.indexOf(normalized);
        if (idx < 0) {
            return PLAN_VIP;
        }
        return TIER_ORDER.get(Math.min(idx + 1, TIER_ORDER.size() - 1));
    }

    public static VipDtos.VipEntitlements defaultEntitlements(String planCode) {
        return switch (normalizePlanCode(planCode)) {
            case PLAN_VIP -> new VipDtos.VipEntitlements(
                    300, false, 50, 20, 20,
                    1_000, 50_000, 1, 0,
                    30, 60L * 1024 * 1024,
                    30L, 150_000L, false
            );
            case PLAN_SVIP -> new VipDtos.VipEntitlements(
                    1_000, false, 100, 50, 60,
                    10_000, 100_000, 3, 0,
                    100, 300L * 1024 * 1024,
                    60L, 400_000L, true
            );
            case PLAN_STUDIO -> new VipDtos.VipEntitlements(
                    2_000, true, 150, 100, 0,
                    50_000, 100_000, 5, 0,
                    500, 2L * 1024 * 1024 * 1024,
                    120L, 1_000_000L, true
            );
            default -> new VipDtos.VipEntitlements(
                    50, false, 20, 5, 3,
                    100, 20_000, 0, 20,
                    5, 10L * 1024 * 1024,
                    10L, 40_000L, false
            );
        };
    }

    private VipPlanEntity builtInPlanFallback(String planCode) {
        String code = normalizePlanCode(planCode);
        VipPlanEntity e = new VipPlanEntity();
        e.setPlanCode(code);
        e.setEnabled(true);
        switch (code) {
            case PLAN_VIP -> {
                e.setDisplayName("进阶作者 VIP");
                e.setMonthlyPriceCents(2900);
                e.setMonthlyGrantMicros(200_000_000L);
                e.setCreditDiscountBps(10000);
                e.setSortOrder(2);
            }
            case PLAN_SVIP -> {
                e.setDisplayName("长篇大神 SVIP");
                e.setMonthlyPriceCents(6900);
                e.setMonthlyGrantMicros(600_000_000L);
                e.setCreditDiscountBps(9000);
                e.setSortOrder(3);
            }
            case PLAN_STUDIO -> {
                e.setDisplayName("工作室万章版");
                e.setMonthlyPriceCents(19900);
                e.setMonthlyGrantMicros(2_000_000_000L);
                e.setCreditDiscountBps(8000);
                e.setSortOrder(4);
            }
            default -> {
                e.setPlanCode(PLAN_FREE);
                e.setDisplayName("免费版");
                e.setMonthlyPriceCents(0);
                e.setMonthlyGrantMicros(20_000_000L);
                e.setCreditDiscountBps(10000);
                e.setSortOrder(1);
            }
        }
        e.setEntitlementsJson(serializeEntitlements(defaultEntitlements(e.getPlanCode())));
        return e;
    }

    private VipDtos.ResolvedUserEntitlements defaultResolvedFree() {
        VipPlanEntity free = builtInPlanFallback(PLAN_FREE);
        return new VipDtos.ResolvedUserEntitlements(
                free.getPlanCode(),
                free.getDisplayName(),
                "FREE_DEFAULT",
                null,
                null,
                free.getCreditDiscountBps(),
                free.getMonthlyGrantMicros(),
                defaultEntitlements(PLAN_FREE)
        );
    }

    private String userCacheKey(UUID userId) {
        String version = "0";
        try {
            String raw = redis.opsForValue().get(REDIS_PLANS_VERSION_KEY);
            if (raw != null && !raw.isBlank()) {
                version = raw.trim();
            }
        } catch (Exception ignored) {
            // Fall back to v0 when Redis is unavailable
        }
        return REDIS_USER_KEY_PREFIX + version + ":" + userId;
    }

    private static String rippleDailyKey(UUID userId) {
        return "foliarc:vip:ripple:daily:" + userId + ":" + LocalDate.now();
    }

    private VipDtos.ResolvedUserEntitlements readCachedUser(String cacheKey) {
        try {
            String raw = redis.opsForValue().get(cacheKey);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            return objectMapper.readValue(raw, VipDtos.ResolvedUserEntitlements.class);
        } catch (Exception ex) {
            return null;
        }
    }

    private void writeCachedUser(String cacheKey, VipDtos.ResolvedUserEntitlements resolved) {
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(resolved), USER_CACHE_TTL);
        } catch (Exception ex) {
            log.debug("Unable to cache VIP entitlements in Redis: {}", ex.getMessage());
        }
    }
}
