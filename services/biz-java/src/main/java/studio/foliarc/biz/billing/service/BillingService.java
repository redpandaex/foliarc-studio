package studio.foliarc.biz.billing.service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.dto.BillingDtos;
import studio.foliarc.biz.billing.entity.BillingAccountEntity;
import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;
import studio.foliarc.biz.billing.entity.GuardrailAuditLogEntity;
import studio.foliarc.biz.billing.mapper.BillingAccountMapper;
import studio.foliarc.biz.billing.mapper.BillingLedgerEntryMapper;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.gateway.RedisRateLimiterService;

/**
 * Enterprise Distributed Two-Phase Idempotent Token Billing Ledger:
 * - Phase 1 (FREEZE): Atomically freezes estimated micro-credits before invoking LLM/Agent,
 *   enforcing both balance and monthly budget hard-stop.
 * - Phase 2 (SETTLE / RELEASE): Reconciles exact input/cached/output tokens + sandbox calls,
 *   refunds unused frozen amount, tracks KV Cache savings, and writes immutable audit ledger.
 * - Concurrency safety: Redis distributed lock (held OUTSIDE DB transaction!) +
 *   MyBatis-Plus {@code @Version} optimistic lock retries + unique {@code request_id} idempotency.
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private static final String UNLOCK_LUA = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            else
                return 0
            end
            """;

    private final BillingAccountMapper accountMapper;
    private final BillingLedgerEntryMapper ledgerMapper;
    private final GuardrailAuditLogMapper auditMapper;
    private final StringRedisTemplate redis;
    private final TransactionTemplate txTemplate;
    private final RedisRateLimiterService rateLimiter;
    private final CircuitBreaker agentCircuitBreaker;
    private final Bulkhead agentBulkhead;
    private final DefaultRedisScript<Long> unlockScript;
    private final long starterGrantMicros;
    private final long defaultMonthlyBudgetMicros;

    public BillingService(
            BillingAccountMapper accountMapper,
            BillingLedgerEntryMapper ledgerMapper,
            GuardrailAuditLogMapper auditMapper,
            StringRedisTemplate redis,
            TransactionTemplate txTemplate,
            RedisRateLimiterService rateLimiter,
            CircuitBreaker agentCircuitBreaker,
            Bulkhead agentBulkhead,
            @Value("${foliarc.billing.starter-grant-micros:50000000}") long starterGrantMicros,
            @Value("${foliarc.billing.default-monthly-budget-micros:100000000}") long defaultMonthlyBudgetMicros
    ) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.auditMapper = auditMapper;
        this.redis = redis;
        this.txTemplate = txTemplate;
        this.rateLimiter = rateLimiter;
        this.agentCircuitBreaker = agentCircuitBreaker;
        this.agentBulkhead = agentBulkhead;
        this.starterGrantMicros = starterGrantMicros;
        this.defaultMonthlyBudgetMicros = defaultMonthlyBudgetMicros;
        this.unlockScript = new DefaultRedisScript<>();
        this.unlockScript.setScriptText(UNLOCK_LUA);
        this.unlockScript.setResultType(Long.class);
    }

    public BillingAccountEntity ensureAccount(UUID userId) {
        BillingAccountEntity existing = accountMapper.selectById(userId);
        if (existing != null) {
            return existing;
        }
        return executeWithAccountLock(userId, () -> txTemplate.execute(status -> {
            BillingAccountEntity rechecked = accountMapper.selectById(userId);
            if (rechecked != null) {
                return rechecked;
            }
            String month = currentMonth();
            BillingAccountEntity acct = new BillingAccountEntity();
            acct.setUserId(userId);
            acct.setBalanceMicros(starterGrantMicros);
            acct.setFrozenMicros(0L);
            acct.setMonthlyBudgetMicros(defaultMonthlyBudgetMicros);
            acct.setMonthlySpentMicros(0L);
            acct.setBudgetMonth(month);
            acct.setVersion(0L);
            accountMapper.insert(acct);

            insertLedger(
                    userId,
                    null,
                    "GRANT:" + userId,
                    "GRANT",
                    starterGrantMicros,
                    starterGrantMicros,
                    0L,
                    null,
                    0,
                    0,
                    0,
                    0L,
                    0,
                    "新用户初始赠送额度 (" + ModelPricing.toCredits(starterGrantMicros) + " Credits)"
            );
            return acct;
        }));
    }

    /**
     * Phase 1: Idempotent Pre-Freeze before dispatching an Agent run.
     */
    public long freezeForRun(UUID userId, UUID runId, String mode, String modelAlias) {
        String reqId = "FREEZE:" + runId;
        BillingLedgerEntryEntity existing = findByRequestId(reqId);
        if (existing != null) {
            return Math.abs(existing.getAmountMicros());
        }
        ensureAccount(userId);
        long freezeAmount = ModelPricing.estimatePreFreezeMicros(mode, modelAlias);

        return executeWithAccountLock(userId, () -> runWithOptimisticRetry(() -> txTemplate.execute(status -> {
            BillingLedgerEntryEntity dup = findByRequestId(reqId);
            if (dup != null) {
                return Math.abs(dup.getAmountMicros());
            }

            BillingAccountEntity acct = accountMapper.selectById(userId);
            if (acct == null) {
                throw BizException.notFound("BILLING_ACCOUNT_MISSING", "计费账户不存在");
            }
            rolloverMonthIfNeeded(acct);

            long projectedMonthly = acct.getMonthlySpentMicros() + acct.getFrozenMicros() + freezeAmount;
            if (projectedMonthly > acct.getMonthlyBudgetMicros()) {
                throw BizException.paymentRequired(
                        "MONTHLY_BUDGET_EXCEEDED",
                        "已触发月度预算硬熔断（上限 " + ModelPricing.toCredits(acct.getMonthlyBudgetMicros())
                                + " Credits），请在计费中心调高月度预算"
                );
            }
            if (acct.getBalanceMicros() < freezeAmount) {
                throw BizException.paymentRequired(
                        "INSUFFICIENT_BALANCE",
                        "账户可用额度不足（当前余额 " + ModelPricing.toCredits(acct.getBalanceMicros())
                                + " Credits，本次预冻结需 " + ModelPricing.toCredits(freezeAmount) + " Credits），请先充值"
                );
            }

            acct.setBalanceMicros(acct.getBalanceMicros() - freezeAmount);
            acct.setFrozenMicros(acct.getFrozenMicros() + freezeAmount);
            if (accountMapper.updateById(acct) == 0) {
                throw new OptimisticRetryException();
            }

            insertLedger(
                    userId,
                    runId,
                    reqId,
                    "FREEZE",
                    -freezeAmount,
                    acct.getBalanceMicros(),
                    acct.getFrozenMicros(),
                    modelAlias,
                    0,
                    0,
                    0,
                    0L,
                    0,
                    "任务启动预冻结 (" + mode + " / " + modelAlias + ")"
            );
            return freezeAmount;
        })));
    }

    /**
     * Phase 2: Idempotent Exact Settlement + Refund of Unused Freeze.
     */
    public long settleRun(
            UUID userId,
            UUID runId,
            long frozenAmountMicros,
            String modelAlias,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            int sandboxCalls,
            String reasonSuffix
    ) {
        if (frozenAmountMicros <= 0 && inputTokens <= 0 && outputTokens <= 0 && sandboxCalls <= 0) {
            return 0L;
        }
        String reqId = "SETTLE:" + runId;
        BillingLedgerEntryEntity existing = findByRequestId(reqId);
        if (existing != null) {
            return existing.getAmountMicros();
        }
        ensureAccount(userId);

        ModelPricing.CostBreakdown cost = ModelPricing.calculateActualCost(
                modelAlias, inputTokens, cachedTokens, outputTokens, sandboxCalls
        );
        long actualCost = cost.totalMicros();

        return executeWithAccountLock(userId, () -> runWithOptimisticRetry(() -> txTemplate.execute(status -> {
            BillingLedgerEntryEntity dup = findByRequestId(reqId);
            if (dup != null) {
                return dup.getAmountMicros();
            }

            BillingAccountEntity acct = accountMapper.selectById(userId);
            if (acct == null) {
                return 0L;
            }
            rolloverMonthIfNeeded(acct);

            long effectiveFreeze = Math.min(Math.max(0L, frozenAmountMicros), acct.getFrozenMicros());
            long newFrozen = acct.getFrozenMicros() - effectiveFreeze;
            long charged;
            long newBalance;

            if (actualCost <= effectiveFreeze) {
                long refund = effectiveFreeze - actualCost;
                charged = actualCost;
                newBalance = acct.getBalanceMicros() + refund;
            } else {
                long extraNeeded = actualCost - effectiveFreeze;
                long extraDeducted = Math.min(extraNeeded, acct.getBalanceMicros());
                charged = effectiveFreeze + extraDeducted;
                newBalance = acct.getBalanceMicros() - extraDeducted;
            }

            acct.setBalanceMicros(newBalance);
            acct.setFrozenMicros(newFrozen);
            acct.setMonthlySpentMicros(acct.getMonthlySpentMicros() + charged);
            if (accountMapper.updateById(acct) == 0) {
                throw new OptimisticRetryException();
            }

            String entryType = charged == 0L ? "RELEASE" : "SETTLE";
            String desc = charged == 0L
                    ? "全额解冻退还预扣额度" + (reasonSuffix != null ? " (" + reasonSuffix + ")" : "")
                    : "精准结算 in=" + inputTokens + " (cache=" + cachedTokens + ") out=" + outputTokens
                            + (sandboxCalls > 0 ? " sandbox=" + sandboxCalls : "")
                            + (reasonSuffix != null ? " [" + reasonSuffix + "]" : "");

            insertLedger(
                    userId,
                    runId,
                    reqId,
                    entryType,
                    charged,
                    newBalance,
                    newFrozen,
                    modelAlias,
                    inputTokens,
                    cachedTokens,
                    outputTokens,
                    cost.cacheSavedMicros(),
                    sandboxCalls,
                    desc
            );
            return charged;
        })));
    }

    public BillingDtos.AccountSummary topUp(UUID userId, double amountCredits, String clientRequestId) {
        ensureAccount(userId);
        long addMicros = Math.round(amountCredits * ModelPricing.MICROS_PER_CREDIT);
        String reqId = (clientRequestId != null && !clientRequestId.isBlank())
                ? "TOPUP:" + clientRequestId.trim()
                : "TOPUP:" + UUID.randomUUID();

        return executeWithAccountLock(userId, () -> runWithOptimisticRetry(() -> txTemplate.execute(status -> {
            BillingAccountEntity acct = accountMapper.selectById(userId);
            if (findByRequestId(reqId) != null) {
                return toAccountSummary(acct);
            }
            acct.setBalanceMicros(acct.getBalanceMicros() + addMicros);
            if (accountMapper.updateById(acct) == 0) {
                throw new OptimisticRetryException();
            }
            insertLedger(
                    userId,
                    null,
                    reqId,
                    "TOPUP",
                    addMicros,
                    acct.getBalanceMicros(),
                    acct.getFrozenMicros(),
                    null,
                    0,
                    0,
                    0,
                    0L,
                    0,
                    "自助充值 +" + ModelPricing.toCredits(addMicros) + " Credits"
            );
            return toAccountSummary(acct);
        })));
    }

    public BillingDtos.AccountSummary updateMonthlyBudget(UUID userId, double monthlyBudgetCredits) {
        ensureAccount(userId);
        long budgetMicros = Math.round(monthlyBudgetCredits * ModelPricing.MICROS_PER_CREDIT);
        return executeWithAccountLock(userId, () -> runWithOptimisticRetry(() -> txTemplate.execute(status -> {
            BillingAccountEntity acct = accountMapper.selectById(userId);
            acct.setMonthlyBudgetMicros(budgetMicros);
            if (accountMapper.updateById(acct) == 0) {
                throw new OptimisticRetryException();
            }
            return toAccountSummary(acct);
        })));
    }

    public BillingDtos.BillingDashboardResponse getDashboard(UUID userId) {
        BillingAccountEntity acct = ensureAccount(userId);
        List<BillingLedgerEntryEntity> entries = ledgerMapper.selectList(
                new LambdaQueryWrapper<BillingLedgerEntryEntity>()
                        .eq(BillingLedgerEntryEntity::getUserId, userId)
                        .orderByDesc(BillingLedgerEntryEntity::getCreatedAt)
                        .last("LIMIT 50")
        );

        long totalCacheSaved = 0L;
        long totalInput = 0L;
        long totalCached = 0L;
        Map<String, long[]> modelAgg = new LinkedHashMap<>();

        for (BillingLedgerEntryEntity e : entries) {
            if ("SETTLE".equals(e.getEntryType())) {
                totalCacheSaved += safeLong(e.getCacheSavedMicros());
                totalInput += safeInt(e.getInputTokens());
                totalCached += safeInt(e.getCachedTokens());
                String alias = (e.getModelAlias() == null || e.getModelAlias().isBlank()) ? "writer-main" : e.getModelAlias();
                long[] acc = modelAgg.computeIfAbsent(alias, k -> new long[6]);
                acc[0] += 1;
                acc[1] += safeLong(e.getAmountMicros());
                acc[2] += safeInt(e.getInputTokens());
                acc[3] += safeInt(e.getCachedTokens());
                acc[4] += safeInt(e.getOutputTokens());
                acc[5] += safeLong(e.getCacheSavedMicros());
            }
        }

        List<BillingDtos.ModelCostStat> breakdown = new ArrayList<>();
        for (var entry : modelAgg.entrySet()) {
            long[] v = entry.getValue();
            breakdown.add(new BillingDtos.ModelCostStat(
                    entry.getKey(),
                    v[0],
                    v[1],
                    ModelPricing.toCredits(v[1]),
                    v[2],
                    v[3],
                    v[4],
                    v[5]
            ));
        }

        double hitRatio = totalInput > 0 ? Math.round((totalCached / (double) totalInput) * 1000.0) / 10.0 : 0.0;
        BillingDtos.GatewayStatusResponse gw = new BillingDtos.GatewayStatusResponse(
                agentCircuitBreaker.getState().name(),
                Math.max(0.0f, agentCircuitBreaker.getMetrics().getFailureRate()),
                agentBulkhead.getMetrics().getAvailableConcurrentCalls(),
                agentBulkhead.getMetrics().getMaxAllowedConcurrentCalls(),
                rateLimiter.getRpmLimit(),
                rateLimiter.getTpmLimit()
        );

        return new BillingDtos.BillingDashboardResponse(
                toAccountSummary(acct),
                ModelPricing.toCredits(totalCacheSaved),
                hitRatio,
                breakdown,
                gw,
                entries.stream().map(BillingService::toLedgerResponse).toList()
        );
    }

    public List<BillingDtos.LedgerEntryResponse> listLedger(UUID userId) {
        ensureAccount(userId);
        return ledgerMapper.selectList(
                new LambdaQueryWrapper<BillingLedgerEntryEntity>()
                        .eq(BillingLedgerEntryEntity::getUserId, userId)
                        .orderByDesc(BillingLedgerEntryEntity::getCreatedAt)
                        .last("LIMIT 100")
        ).stream().map(BillingService::toLedgerResponse).toList();
    }

    public void recordGuardrailAudit(BillingDtos.InternalGuardrailAuditRequest req) {
        GuardrailAuditLogEntity entity = new GuardrailAuditLogEntity();
        entity.setId(UUID.randomUUID());
        entity.setUserId(req.userId());
        entity.setBookId(req.bookId());
        entity.setRunId(req.runId());
        entity.setStage(req.stage() != null ? req.stage() : "INPUT");
        entity.setAction(req.action() != null ? req.action() : "BLOCK");
        entity.setRuleCode(req.ruleCode() != null ? req.ruleCode() : "UNKNOWN");
        entity.setDetail(req.detail() != null ? req.detail() : "");
        auditMapper.insert(entity);
    }

    // --- Concurrency & Lock Helpers ---

    private <T> T executeWithAccountLock(UUID userId, Supplier<T> action) {
        String lockKey = "foliarc:lock:billing:" + userId;
        String token = UUID.randomUUID().toString();
        boolean acquired = false;
        try {
            for (int attempt = 0; attempt < 10; attempt++) {
                Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(5));
                if (Boolean.TRUE.equals(ok)) {
                    acquired = true;
                    break;
                }
                Thread.sleep(30);
            }
        } catch (Exception ex) {
            log.debug("Billing Redis lock fallback to optimistic DB lock only: {}", ex.getMessage());
        }

        try {
            return action.get();
        } finally {
            if (acquired) {
                try {
                    redis.execute(unlockScript, List.of(lockKey), token);
                } catch (Exception ignored) {
                    // Lock expires via TTL
                }
            }
        }
    }

    private <T> T runWithOptimisticRetry(Supplier<T> action) {
        for (int i = 0; i < 4; i++) {
            try {
                return action.get();
            } catch (OptimisticRetryException | DuplicateKeyException ex) {
                if (i == 3) {
                    throw BizException.conflict("BILLING_CONFLICT", "账户并发结算冲突，请稍后重试");
                }
            }
        }
        throw BizException.conflict("BILLING_CONFLICT", "账户并发结算冲突");
    }

    private BillingLedgerEntryEntity findByRequestId(String reqId) {
        return ledgerMapper.selectOne(
                new LambdaQueryWrapper<BillingLedgerEntryEntity>()
                        .eq(BillingLedgerEntryEntity::getRequestId, reqId)
        );
    }

    private void insertLedger(
            UUID userId,
            UUID runId,
            String requestId,
            String entryType,
            long amountMicros,
            long balanceAfter,
            long frozenAfter,
            String modelAlias,
            int inputTokens,
            int cachedTokens,
            int outputTokens,
            long cacheSavedMicros,
            int sandboxCalls,
            String description
    ) {
        BillingLedgerEntryEntity e = new BillingLedgerEntryEntity();
        e.setId(UUID.randomUUID());
        e.setUserId(userId);
        e.setRunId(runId);
        e.setRequestId(requestId);
        e.setEntryType(entryType);
        e.setAmountMicros(amountMicros);
        e.setBalanceAfterMicros(balanceAfter);
        e.setFrozenAfterMicros(frozenAfter);
        e.setModelAlias(modelAlias);
        e.setInputTokens(inputTokens);
        e.setCachedTokens(cachedTokens);
        e.setOutputTokens(outputTokens);
        e.setCacheSavedMicros(cacheSavedMicros);
        e.setSandboxCalls(sandboxCalls);
        e.setDescription(description);
        ledgerMapper.insert(e);
    }

    private static void rolloverMonthIfNeeded(BillingAccountEntity acct) {
        String nowMonth = currentMonth();
        if (!nowMonth.equals(acct.getBudgetMonth())) {
            acct.setBudgetMonth(nowMonth);
            acct.setMonthlySpentMicros(0L);
        }
    }

    private static String currentMonth() {
        return YearMonth.now().toString();
    }

    private static long safeLong(Long v) { return v == null ? 0L : v; }
    private static int safeInt(Integer v) { return v == null ? 0 : v; }

    private static BillingDtos.AccountSummary toAccountSummary(BillingAccountEntity a) {
        long bal = safeLong(a.getBalanceMicros());
        long frz = safeLong(a.getFrozenMicros());
        long bud = Math.max(1L, safeLong(a.getMonthlyBudgetMicros()));
        long sp = safeLong(a.getMonthlySpentMicros());
        double ratio = Math.min(100.0, Math.round((sp / (double) bud) * 1000.0) / 10.0);
        return new BillingDtos.AccountSummary(
                a.getUserId(),
                bal, ModelPricing.toCredits(bal),
                frz, ModelPricing.toCredits(frz),
                bud, ModelPricing.toCredits(bud),
                sp, ModelPricing.toCredits(sp),
                a.getBudgetMonth(),
                ratio
        );
    }

    private static BillingDtos.LedgerEntryResponse toLedgerResponse(BillingLedgerEntryEntity e) {
        long amt = safeLong(e.getAmountMicros());
        long balAfter = safeLong(e.getBalanceAfterMicros());
        long saved = safeLong(e.getCacheSavedMicros());
        return new BillingDtos.LedgerEntryResponse(
                e.getId(), e.getRunId(), e.getRequestId(), e.getEntryType(),
                amt, ModelPricing.toCredits(amt),
                balAfter, ModelPricing.toCredits(balAfter),
                safeLong(e.getFrozenAfterMicros()),
                e.getModelAlias(),
                safeInt(e.getInputTokens()), safeInt(e.getCachedTokens()), safeInt(e.getOutputTokens()),
                saved, ModelPricing.toCredits(saved),
                safeInt(e.getSandboxCalls()),
                e.getDescription(),
                e.getCreatedAt()
        );
    }

    private static final class OptimisticRetryException extends RuntimeException {}
}
