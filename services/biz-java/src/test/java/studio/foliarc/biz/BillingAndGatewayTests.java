package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.entity.BillingAccountEntity;
import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;
import studio.foliarc.biz.billing.mapper.BillingAccountMapper;
import studio.foliarc.biz.billing.mapper.BillingLedgerEntryMapper;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.gateway.RedisRateLimiterService;

class BillingAndGatewayTests {

    @Test
    void modelPricingCalculatesPromptCacheSavingsAndSandboxCost() {
        // writer-main: input=2000/1K, cached=200/1K, output=8000/1K, sandbox=5000/call
        ModelPricing.CostBreakdown cost = ModelPricing.calculateActualCost(
                "writer-main", 10_000, 8_000, 1_000, 2
        );
        // uncached 2K * 2000 = 4000; cached 8K * 200 = 1600; output 1K * 8000 = 8000; sandbox 2 * 5000 = 10000
        assertThat(cost.totalMicros()).isEqualTo(23_600L);
        // cache saved: 8K * (2000 - 200) = 14400
        assertThat(cost.cacheSavedMicros()).isEqualTo(14_400L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void twoPhaseBillingFreezeSettleRefundAndIdempotencyPreventOvercharge() {
        BillingAccountMapper accountMapper = mock(BillingAccountMapper.class);
        BillingLedgerEntryMapper ledgerMapper = mock(BillingLedgerEntryMapper.class);
        GuardrailAuditLogMapper auditMapper = mock(GuardrailAuditLogMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);

        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<Object> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });

        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        BillingAccountEntity acct = new BillingAccountEntity();
        acct.setUserId(userId);
        acct.setBalanceMicros(1_000_000L); // 1.0 Credit
        acct.setFrozenMicros(0L);
        acct.setMonthlyBudgetMicros(5_000_000L);
        acct.setMonthlySpentMicros(0L);
        acct.setBudgetMonth(YearMonth.now().toString());
        acct.setVersion(0L);

        when(accountMapper.selectById(userId)).thenReturn(acct);
        when(accountMapper.updateById(any(BillingAccountEntity.class))).thenReturn(1);

        Map<String, BillingLedgerEntryEntity> ledgerByReq = new ConcurrentHashMap<>();
        when(ledgerMapper.insert(any(BillingLedgerEntryEntity.class))).thenAnswer(inv -> {
            BillingLedgerEntryEntity e = inv.getArgument(0);
            ledgerByReq.put(e.getRequestId(), e);
            return 1;
        });
        when(ledgerMapper.selectOne(any())).thenAnswer(inv -> {
            // Simple lookup for FREEZE / SETTLE idempotency replay checks
            String sql = inv.getArgument(0).toString();
            for (var entry : ledgerByReq.entrySet()) {
                if (sql.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        });

        RedisRateLimiterService limiter = new RedisRateLimiterService(redis, 20, 120_000);
        BillingService billing = new BillingService(
                accountMapper, ledgerMapper, auditMapper, redis, tx, limiter,
                CircuitBreaker.ofDefaults("cb"), Bulkhead.ofDefaults("bh"),
                50_000_000L, 100_000_000L
        );

        // Phase 1: Freeze 150,000 micros for AGENT run
        long frozen = billing.freezeForRun(userId, runId, "AGENT", "writer-main");
        assertThat(frozen).isEqualTo(150_000L);
        assertThat(acct.getBalanceMicros()).isEqualTo(850_000L);
        assertThat(acct.getFrozenMicros()).isEqualTo(150_000L);

        // Phase 2: Settle actual usage (2000 input, 1000 cached, 500 output -> 2000+200+4000 = 6200 micros)
        long charged = billing.settleRun(userId, runId, frozen, "writer-main", 2000, 1000, 500, 0, "COMPLETED");
        assertThat(charged).isEqualTo(6_200L);
        // Unused 143,800 refunded! Final balance = 1,000,000 - 6,200 = 993,800; frozen = 0
        assertThat(acct.getBalanceMicros()).isEqualTo(993_800L);
        assertThat(acct.getFrozenMicros()).isZero();
        assertThat(acct.getMonthlySpentMicros()).isEqualTo(6_200L);

        // Enforce monthly budget hard-stop
        acct.setMonthlyBudgetMicros(10_000L);
        assertThatThrownBy(() -> billing.freezeForRun(userId, UUID.randomUUID(), "AGENT", "writer-main"))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
                    assertThat(biz.getCode()).isEqualTo("MONTHLY_BUDGET_EXCEEDED");
                });
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisLuaRateLimiterEnforcesRpmAndTpmLimitsWith429() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(1L, 0L, 19L, 118_000L))
                .thenReturn(List.of(0L, 1L, 0L, 100_000L))
                .thenReturn(List.of(0L, 2L, 10L, 0L));

        RedisRateLimiterService limiter = new RedisRateLimiterService(redis, 20, 120_000);
        UUID userId = UUID.randomUUID();

        var ok = limiter.acquireOrThrow(userId, 2000);
        assertThat(ok.allowed()).isTrue();
        assertThat(ok.remainingRpm()).isEqualTo(19L);

        assertThatThrownBy(() -> limiter.acquireOrThrow(userId, 2000))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(biz.getCode()).isEqualTo("RATE_LIMIT_RPM");
                });

        assertThatThrownBy(() -> limiter.acquireOrThrow(userId, 50_000))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> {
                    BizException biz = (BizException) ex;
                    assertThat(biz.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                    assertThat(biz.getCode()).isEqualTo("RATE_LIMIT_TPM");
                });
    }
}
