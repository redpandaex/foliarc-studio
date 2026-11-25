package studio.foliarc.biz.vip.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class VipDtos {

    private VipDtos() {}

    public record VipEntitlements(
            int rippleMaxRangeChapters,
            boolean rippleAllowFullBook,
            int rippleMaxCandidates,
            int rippleMaxPatchChapters,
            int rippleDailyScanLimit,
            int maxMemoryChapters,
            int maxSectionChars,
            int maxStyleAnchors,
            int maxForeshadowings,
            int userLibraryMaxDocs,
            long userLibraryMaxTotalBytes,
            long rpmLimit,
            long tpmLimit,
            boolean priorityQueue
    ) {}

    public record ResolvedUserEntitlements(
            String planCode,
            String displayName,
            String subscriptionStatus,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            int creditDiscountBps,
            long monthlyGrantMicros,
            VipEntitlements entitlements
    ) {}

    public record VipPlanResponse(
            String planCode,
            String displayName,
            int monthlyPriceCents,
            double monthlyPriceCredits,
            long monthlyGrantMicros,
            double monthlyGrantCredits,
            int creditDiscountBps,
            VipEntitlements entitlements,
            boolean enabled,
            int sortOrder
    ) {}

    public record LibraryQuotaUsage(
            int usedDocs,
            int maxDocs,
            long usedBytes,
            long maxTotalBytes
    ) {}

    public record MyVipStatusResponse(
            String planCode,
            String displayName,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime expiresAt,
            int creditDiscountBps,
            double balanceCredits,
            int rippleScansUsedToday,
            int rippleDailyScanLimit,
            LibraryQuotaUsage libraryUsage,
            VipEntitlements entitlements
    ) {}

    public record SubscribeVipRequest(
            @NotBlank(message = "套餐代码不能为空")
            @Size(max = 24)
            String planCode,

            @Min(value = 1, message = "开通月数最少为 1")
            @Max(value = 24, message = "单次开通月数最多为 24")
            Integer months,

            @Size(max = 32)
            String paymentChannel,

            @Size(max = 128)
            String idempotencyKey
    ) {}

    public record SubscribeVipResponse(
            UUID orderId,
            String planCode,
            String displayName,
            int durationDays,
            double chargedCredits,
            double grantedBonusCredits,
            OffsetDateTime expiresAt,
            boolean idempotentReplay
    ) {}

    public record AdminGrantVipRequest(
            @NotNull(message = "目标用户 ID 不能为空")
            UUID userId,

            @NotBlank(message = "套餐代码不能为空")
            @Size(max = 24)
            String planCode,

            @Min(value = 1, message = "授予天数最少为 1")
            @Max(value = 3650, message = "授予天数最多为 3650")
            Integer durationDays,

            Boolean grantBonusCredits,

            @Size(max = 128)
            String idempotencyKey
    ) {}

    public record AdminUpdateVipPlanRequest(
            @Size(max = 64)
            String displayName,

            @Min(0)
            Integer monthlyPriceCents,

            @Min(0)
            Long monthlyGrantMicros,

            @Min(1000)
            @Max(10000)
            Integer creditDiscountBps,

            VipEntitlements entitlements,

            Boolean enabled
    ) {}
}
