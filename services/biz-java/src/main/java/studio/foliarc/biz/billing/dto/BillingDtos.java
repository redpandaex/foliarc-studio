package studio.foliarc.biz.billing.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public final class BillingDtos {

    private BillingDtos() {}

    public record AccountSummary(
            UUID userId,
            long balanceMicros,
            double balanceCredits,
            long frozenMicros,
            double frozenCredits,
            long monthlyBudgetMicros,
            double monthlyBudgetCredits,
            long monthlySpentMicros,
            double monthlySpentCredits,
            String budgetMonth,
            double budgetUsageRatio
    ) {}

    public record LedgerEntryResponse(
            UUID id,
            UUID runId,
            String requestId,
            String entryType,
            long amountMicros,
            double amountCredits,
            long balanceAfterMicros,
            double balanceAfterCredits,
            long frozenAfterMicros,
            String modelAlias,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            long cacheSavedMicros,
            double cacheSavedCredits,
            int sandboxCalls,
            String description,
            OffsetDateTime createdAt
    ) {}

    public record ModelCostStat(
            String modelAlias,
            long runsCount,
            long totalSpentMicros,
            double totalSpentCredits,
            long totalInputTokens,
            long totalCachedTokens,
            long totalOutputTokens,
            long totalCacheSavedMicros
    ) {}

    public record GatewayStatusResponse(
            String circuitBreakerState,
            float circuitBreakerFailureRate,
            int bulkheadAvailableCalls,
            int bulkheadMaxConcurrentCalls,
            long rpmLimit,
            long tpmLimit
    ) {}

    public record BillingDashboardResponse(
            AccountSummary account,
            double totalCacheSavedCredits,
            double cacheHitRatio,
            List<ModelCostStat> modelBreakdown,
            GatewayStatusResponse gateway,
            List<LedgerEntryResponse> recentLedger
    ) {}

    public record TopUpRequest(
            @NotNull @DecimalMin("1.0") @DecimalMax("10000.0") Double amountCredits,
            String requestId
    ) {}

    public record UpdateBudgetRequest(
            @NotNull @DecimalMin("1.0") @DecimalMax("100000.0") Double monthlyBudgetCredits
    ) {}

    public record InternalGuardrailAuditRequest(
            UUID userId,
            UUID bookId,
            UUID runId,
            String stage,
            String action,
            String ruleCode,
            String detail
    ) {}
}
