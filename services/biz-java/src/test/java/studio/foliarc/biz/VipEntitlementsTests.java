package studio.foliarc.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import reactor.core.publisher.Mono;
import studio.foliarc.biz.billing.ModelPricing;
import studio.foliarc.biz.billing.entity.BillingAccountEntity;
import studio.foliarc.biz.billing.entity.BillingLedgerEntryEntity;
import studio.foliarc.biz.billing.mapper.BillingAccountMapper;
import studio.foliarc.biz.billing.mapper.BillingLedgerEntryMapper;
import studio.foliarc.biz.billing.mapper.GuardrailAuditLogMapper;
import studio.foliarc.biz.billing.service.BillingService;
import studio.foliarc.biz.common.BizException;
import studio.foliarc.biz.gateway.RedisRateLimiterService;
import studio.foliarc.biz.novel.entity.BookEntity;
import studio.foliarc.biz.novel.mapper.ChapterMapper;
import studio.foliarc.biz.novel.service.BookService;
import studio.foliarc.biz.outbox.RabbitMqTopologyConfig;
import studio.foliarc.biz.outbox.service.OutboxService;
import studio.foliarc.biz.rag.entity.KnowledgeDocumentEntity;
import studio.foliarc.biz.rag.mapper.BookKnowledgeBindingMapper;
import studio.foliarc.biz.rag.mapper.DocumentChunkMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeDocumentMapper;
import studio.foliarc.biz.rag.mapper.KnowledgeGrantMapper;
import studio.foliarc.biz.rag.service.KnowledgeDocumentService;
import studio.foliarc.biz.run.dto.RunDtos;
import studio.foliarc.biz.run.mapper.AgentCheckpointMapper;
import studio.foliarc.biz.run.mapper.AgentRunMapper;
import studio.foliarc.biz.run.service.AgentRunService;
import studio.foliarc.biz.storage.MinioStorageService;
import studio.foliarc.biz.vip.dto.VipDtos;
import studio.foliarc.biz.vip.entity.UserSubscriptionEntity;
import studio.foliarc.biz.vip.entity.VipOrderEntity;
import studio.foliarc.biz.vip.entity.VipPlanEntity;
import studio.foliarc.biz.vip.mapper.UserSubscriptionMapper;
import studio.foliarc.biz.vip.mapper.VipOrderMapper;
import studio.foliarc.biz.vip.mapper.VipPlanMapper;
import studio.foliarc.biz.vip.service.VipEntitlementService;
import studio.foliarc.biz.vip.service.VipSubscriptionService;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class VipEntitlementsTests {

    private static final long BYTES_PER_MB = 1024L * 1024;

    private final UUID userId = UUID.randomUUID();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock private VipPlanMapper planMapper;
    @Mock private UserSubscriptionMapper subscriptionMapper;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> redisValues;

    @Test
    void resolveForUserCachesActivePlanEntitlementsAndReadsThemBack() {
        VipEntitlementService service = entitlementService();
        UserSubscriptionEntity subscription = subscription("SVIP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(10));
        VipPlanEntity plan = plan("SVIP", "长篇大神 SVIP", 6_900, 600_000_000L, 9_000);
        VipDtos.VipEntitlements entitlements = new VipDtos.VipEntitlements(
                1_000, false, 100, 50, 60, 10_000, 100_000, 3, 0,
                120, 350 * BYTES_PER_MB, 72, 500_000, true
        );
        plan.setEntitlementsJson(objectMapper.writeValueAsString(entitlements));
        when(subscriptionMapper.selectOne(any())).thenReturn(subscription);
        when(planMapper.selectById("SVIP")).thenReturn(plan);
        when(redisValues.get(VipEntitlementService.REDIS_PLANS_VERSION_KEY)).thenReturn("7");

        VipDtos.ResolvedUserEntitlements resolved = service.resolveForUser(userId);

        assertThat(resolved).isEqualTo(new VipDtos.ResolvedUserEntitlements(
                "SVIP", plan.getDisplayName(), "ACTIVE", subscription.getStartedAt(), subscription.getExpiresAt(),
                9_000, 600_000_000L, entitlements
        ));
        String cacheKey = userCacheKey("7");
        var cachedJson = ArgumentCaptor.forClass(String.class);
        verify(redisValues).set(eq(cacheKey), cachedJson.capture(), eq(Duration.ofMinutes(5)));
        when(redisValues.get(cacheKey)).thenReturn(cachedJson.getValue());

        assertThat(service.resolveForUser(userId)).isEqualTo(resolved);
        verify(subscriptionMapper).selectOne(any());
        verify(planMapper).selectById("SVIP");
        verifyNoMoreInteractions(subscriptionMapper, planMapper);
    }

    @Test
    void resolveForUserDowngradesExpiredSubscriptionEvenWithCachedPaidEntitlements() {
        VipEntitlementService service = entitlementService();
        OffsetDateTime expiredAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
        UserSubscriptionEntity subscription = subscription("SVIP", expiredAt);
        var stale = new VipDtos.ResolvedUserEntitlements(
                "SVIP", "长篇大神 SVIP", "ACTIVE", subscription.getStartedAt(), expiredAt,
                9_000, 600_000_000L, VipEntitlementService.defaultEntitlements("SVIP")
        );
        when(redisValues.get(userCacheKey("0"))).thenReturn(objectMapper.writeValueAsString(stale));
        when(subscriptionMapper.selectOne(any())).thenReturn(subscription);

        VipDtos.ResolvedUserEntitlements resolved = service.resolveForUser(userId);

        assertThat(resolved.planCode()).isEqualTo("FREE");
        assertThat(resolved.subscriptionStatus()).isEqualTo("EXPIRED");
        assertThat(resolved.expiresAt()).isEqualTo(expiredAt);
        assertThat(resolved.entitlements()).isEqualTo(VipEntitlementService.defaultEntitlements("FREE"));
        verify(redis).delete(userCacheKey("0"));
        verify(subscriptionMapper).selectOne(any());
        verifyNoMoreInteractions(subscriptionMapper);
    }

    @Test
    void evictAllPlanCachesIncrementsRedisPlansVersion() {
        entitlementService().evictAllPlanCaches();

        verify(redisValues).increment(VipEntitlementService.REDIS_PLANS_VERSION_KEY);
    }

    @Nested
    class SubscriptionChanges {

        @Mock private VipOrderMapper orderMapper;
        @Mock private BillingService billingService;

        private VipSubscriptionService service;

        @BeforeEach
        void setUp() {
            service = new VipSubscriptionService(
                    entitlementService(), planMapper, subscriptionMapper, orderMapper,
                    billingService, mock(KnowledgeDocumentMapper.class)
            );
        }

        @Test
        void subscribeChargesAndGrantsCreditsAndReplaysTheSameOrderOnce() {
            when(planMapper.selectById("VIP")).thenReturn(plan("VIP", "进阶作者 VIP", 2_900, 200_000_000L, 10_000));
            var savedOrder = new AtomicReference<VipOrderEntity>();
            var savedSubscription = new AtomicReference<UserSubscriptionEntity>();
            when(orderMapper.selectOne(any())).thenAnswer(call -> savedOrder.get());
            when(orderMapper.insert(any(VipOrderEntity.class))).thenAnswer(call -> {
                savedOrder.set(call.getArgument(0));
                return 1;
            });
            when(subscriptionMapper.selectOne(any())).thenAnswer(call -> savedSubscription.get());
            when(subscriptionMapper.insert(any(UserSubscriptionEntity.class))).thenAnswer(call -> {
                savedSubscription.set(call.getArgument(0));
                return 1;
            });
            var request = new VipDtos.SubscribeVipRequest("VIP", 2, "CREDITS", "vip-purchase");

            VipDtos.SubscribeVipResponse original = service.subscribe(userId, request);
            VipDtos.SubscribeVipResponse replay = service.subscribe(userId, request);

            VipOrderEntity order = savedOrder.get();
            UserSubscriptionEntity subscription = savedSubscription.get();
            assertThat(original.orderId()).isNotNull().isEqualTo(order.getId());
            assertThat(original.planCode()).isEqualTo("VIP");
            assertThat(original.durationDays()).isEqualTo(60);
            assertThat(original.chargedCredits()).isEqualTo(58.0);
            assertThat(original.grantedBonusCredits()).isEqualTo(400.0);
            assertThat(original.expiresAt()).isEqualTo(subscription.getExpiresAt());
            assertThat(original.idempotentReplay()).isFalse();
            assertThat(replay.idempotentReplay()).isTrue();
            assertThat(replay).usingRecursiveComparison().ignoringFields("idempotentReplay").isEqualTo(original);

            assertThat(order.getUserId()).isEqualTo(userId);
            assertThat(order.getPlanCode()).isEqualTo("VIP");
            assertThat(order.getAmountCents()).isEqualTo(5_800);
            assertThat(order.getPaymentChannel()).isEqualTo("CREDITS");
            assertThat(order.getStatus()).isEqualTo("PAID");
            assertThat(order.getIdempotencyKey()).isEqualTo(request.idempotencyKey());
            assertThat(subscription.getUserId()).isEqualTo(userId);
            assertThat(subscription.getPlanCode()).isEqualTo("VIP");
            assertThat(subscription.getStatus()).isEqualTo("ACTIVE");
            assertThat(subscription.getLastOrderId()).isEqualTo(order.getId().toString());
            assertThat(subscription.getExpiresAt()).isEqualTo(subscription.getStartedAt().plusDays(60));

            verify(billingService).chargeForVipSubscription(eq(userId), eq(58_000_000L), eq("vip-purchase"), anyString());
            verify(billingService).grantVipCredits(eq(userId), eq(400_000_000L), eq("vip-purchase"), anyString());
            verifyNoMoreInteractions(billingService);
            verify(orderMapper).insert(any(VipOrderEntity.class));
            verify(subscriptionMapper).insert(any(UserSubscriptionEntity.class));
            verify(subscriptionMapper, never()).updateById(any(UserSubscriptionEntity.class));
            verify(redis).delete(userCacheKey("0"));
        }

        @Test
        void adminGrantUpdatesSubscriptionAndEvictsTheRecipientsCache() {
            UUID adminId = UUID.randomUUID();
            when(planMapper.selectById("SVIP")).thenReturn(plan("SVIP", "长篇大神 SVIP", 6_900, 600_000_000L, 9_000));
            UserSubscriptionEntity existing = subscription("VIP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(10));
            when(subscriptionMapper.selectOne(any())).thenReturn(existing);

            VipDtos.SubscribeVipResponse grant = service.adminGrant(
                    adminId, new VipDtos.AdminGrantVipRequest(userId, "SVIP", 35, true, "admin-grant")
            );

            var order = ArgumentCaptor.forClass(VipOrderEntity.class);
            verify(orderMapper).insert(order.capture());
            assertThat(order.getValue().getId()).isEqualTo(grant.orderId());
            assertThat(order.getValue().getUserId()).isEqualTo(userId);
            assertThat(order.getValue().getPaymentChannel()).isEqualTo("ADMIN_GRANT");
            assertThat(order.getValue().getAmountCents()).isZero();
            assertThat(grant.chargedCredits()).isZero();
            assertThat(grant.grantedBonusCredits()).isEqualTo(1_200.0);
            assertThat(existing.getPlanCode()).isEqualTo("SVIP");
            assertThat(existing.getLastOrderId()).isEqualTo(grant.orderId().toString());
            assertThat(grant.expiresAt()).isEqualTo(existing.getStartedAt().plusDays(35));
            verify(subscriptionMapper).updateById(existing);
            verify(billingService).grantVipCredits(eq(userId), eq(1_200_000_000L), eq("admin-grant"), anyString());
            verifyNoMoreInteractions(billingService);
            verify(redis).delete(userCacheKey("0"));
        }

        @Test
        void adminUpdatePlanPersistsEntitlementsAndInvalidatesAllUserPlanCaches() {
            VipPlanEntity plan = plan("VIP", "进阶作者 VIP", 2_900, 200_000_000L, 10_000);
            when(planMapper.selectById("VIP")).thenReturn(plan);
            var updatedEntitlements = new VipDtos.VipEntitlements(
                    350, false, 50, 20, 20, 1_000, 50_000, 1, 0,
                    40, 80 * BYTES_PER_MB, 35, 180_000, false
            );

            VipDtos.VipPlanResponse response = service.adminUpdatePlan(
                    "vip", new VipDtos.AdminUpdateVipPlanRequest("更新后的 VIP", 3_500, null, 9_500, updatedEntitlements, null)
            );

            assertThat(response.planCode()).isEqualTo("VIP");
            assertThat(response.displayName()).isEqualTo("更新后的 VIP");
            assertThat(response.monthlyPriceCents()).isEqualTo(3_500);
            assertThat(response.creditDiscountBps()).isEqualTo(9_500);
            assertThat(response.entitlements()).isEqualTo(updatedEntitlements);
            assertThat(objectMapper.readValue(plan.getEntitlementsJson(), VipDtos.VipEntitlements.class))
                    .isEqualTo(updatedEntitlements);
            verify(planMapper).updateById(plan);
            verify(redisValues).increment(VipEntitlementService.REDIS_PLANS_VERSION_KEY);
        }
    }

    @Nested
    class PersonalLibraryUploads {

        @Mock private KnowledgeDocumentMapper documentMapper;
        @Mock private MinioStorageService minioStorage;
        @Mock private OutboxService outboxService;

        private final MockMultipartFile file = new MockMultipartFile("file", "notes.md", "text/plain", new byte[]{'x'});
        private KnowledgeDocumentService documents;

        @BeforeEach
        void setUp() {
            documents = new KnowledgeDocumentService(
                    mock(BookService.class), mock(ChapterMapper.class), documentMapper, mock(DocumentChunkMapper.class),
                    mock(KnowledgeGrantMapper.class), mock(BookKnowledgeBindingMapper.class), minioStorage,
                    outboxService, mock(RestClient.class)
            );
            documents.setVipEntitlementService(entitlementService());
        }

        @Test
        void freePlanRejectsSixthDocumentAndRecommendsVip() {
            when(documentMapper.selectList(any())).thenReturn(fiveDocuments());

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> documents.uploadPersonalDocument(userId, file, null, null, null))
                    .satisfies(ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getCode()).isEqualTo("VIP_QUOTA_EXCEEDED");
                        assertThat(ex.getDetails())
                                .containsEntry("feature", "LIBRARY_DOCS")
                                .containsEntry("currentPlan", "FREE")
                                .containsEntry("currentLimit", "5")
                                .containsEntry("requested", "6")
                                .containsEntry("recommendedPlan", "VIP");
                    });
            verify(documentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
            verifyNoInteractions(minioStorage, outboxService);
        }

        @Test
        void vipPlanAcceptsSixthDocument() {
            var activeVip = subscription("VIP", OffsetDateTime.now(ZoneOffset.UTC).plusDays(10));
            when(subscriptionMapper.selectOne(any())).thenReturn(activeVip);
            when(documentMapper.selectList(any())).thenReturn(fiveDocuments());

            var uploaded = documents.uploadPersonalDocument(userId, file, null, null, null);

            assertThat(uploaded.scope()).isEqualTo("USER");
            assertThat(uploaded.fileName()).isEqualTo("notes.md");
            assertThat(uploaded.status()).isEqualTo("QUEUED");
            var inserted = ArgumentCaptor.forClass(KnowledgeDocumentEntity.class);
            verify(documentMapper).insert(inserted.capture());
            assertThat(inserted.getValue().getId()).isEqualTo(uploaded.id());
            assertThat(inserted.getValue().getOwnerId()).isEqualTo(userId);
            verify(minioStorage).upload(
                    eq(inserted.getValue().getMinioObjectKey()), any(InputStream.class), eq(file.getSize()), eq(file.getContentType())
            );
            verify(outboxService).enqueue(
                    eq("DOCUMENT"), eq(uploaded.id()), eq("DOC_INGEST"), eq(RabbitMqTopologyConfig.RK_INGEST),
                    eq("DOC_INGEST:" + uploaded.id() + ":v1"), anyMap()
            );
        }

        @Test
        void freePlanReportsStorageQuotaWhenUploadWouldExceedItByOneByte() {
            when(documentMapper.selectList(any())).thenReturn(List.of(documentWithSize(10 * BYTES_PER_MB)));

            assertThatExceptionOfType(BizException.class)
                    .isThrownBy(() -> documents.uploadPersonalDocument(userId, file, null, null, null))
                    .satisfies(ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(ex.getCode()).isEqualTo("VIP_QUOTA_EXCEEDED");
                        assertThat(ex.getDetails())
                                .containsEntry("feature", "LIBRARY_STORAGE_MB")
                                .containsEntry("currentLimit", "10")
                                .containsEntry("requested", "11")
                                .containsEntry("recommendedPlan", "VIP")
                                .containsEntry("recommendedLimit", "60");
                    });
            verify(documentMapper, never()).insert(any(KnowledgeDocumentEntity.class));
            verifyNoInteractions(minioStorage, outboxService);
        }
    }

    @ParameterizedTest
    @CsvSource({"SVIP, 9000, 90", "STUDIO, 8000, 80"})
    void settleRunChargesTheDiscountedCost(String planCode, int discountBps, int chargedPercent) {
        var accountMapper = mock(BillingAccountMapper.class);
        var ledgerMapper = mock(BillingLedgerEntryMapper.class);
        var transaction = mock(TransactionTemplate.class);
        when(transaction.execute(any())).thenAnswer(call -> {
            TransactionCallback<Long> callback = call.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(redis.opsForValue()).thenReturn(redisValues);
        when(redisValues.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        long openingBalance = 1_000_000L;
        long frozen = 150_000L;
        var account = new BillingAccountEntity();
        account.setUserId(userId);
        account.setBalanceMicros(openingBalance - frozen);
        account.setFrozenMicros(frozen);
        account.setMonthlySpentMicros(0L);
        account.setBudgetMonth(YearMonth.now().toString());
        when(accountMapper.selectById(userId)).thenReturn(account);
        when(accountMapper.updateById(account)).thenReturn(1);

        var billing = new BillingService(
                accountMapper, ledgerMapper, mock(GuardrailAuditLogMapper.class), redis, transaction,
                mock(RedisRateLimiterService.class), CircuitBreaker.ofDefaults("billing"), Bulkhead.ofDefaults("billing"),
                50_000_000L, 100_000_000L
        );
        var vip = mock(VipEntitlementService.class);
        when(vip.resolveForUser(userId)).thenReturn(activeEntitlements(planCode, discountBps));
        billing.setVipEntitlementService(vip);
        UUID runId = UUID.randomUUID();
        long baseCost = ModelPricing.calculateActualCost("writer-main", 10_000, 8_000, 1_000, 2).totalMicros();
        long expectedCharge = baseCost * chargedPercent / 100;

        long charged = billing.settleRun(userId, runId, frozen, "writer-main", 10_000, 8_000, 1_000, 2, "COMPLETED");

        assertThat(charged).isEqualTo(expectedCharge);
        assertThat(account.getBalanceMicros()).isEqualTo(openingBalance - expectedCharge);
        assertThat(account.getFrozenMicros()).isZero();
        assertThat(account.getMonthlySpentMicros()).isEqualTo(expectedCharge);
        var ledgerEntry = ArgumentCaptor.forClass(BillingLedgerEntryEntity.class);
        verify(ledgerMapper).insert(ledgerEntry.capture());
        assertThat(ledgerEntry.getValue().getRequestId()).isEqualTo("SETTLE:" + runId);
        assertThat(ledgerEntry.getValue().getAmountMicros()).isEqualTo(expectedCharge);
    }

    @Test
    void startRunUsesTheSvipRpmAndTpmLimits() {
        var rateLimiter = mock(RedisRateLimiterService.class);
        var bookService = mock(BookService.class);
        var book = new BookEntity();
        book.setId(UUID.randomUUID());
        book.setTitle("Test book");
        when(bookService.requireOwnedBook(userId, book.getId())).thenReturn(book);
        WebClient agentClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.ACCEPTED).build()))
                .build();
        var runs = new AgentRunService(
                mock(AgentRunMapper.class), mock(AgentCheckpointMapper.class), bookService, mock(ChapterMapper.class),
                agentClient, redis, rateLimiter, mock(BillingService.class), CircuitBreaker.ofDefaults("agent"),
                Bulkhead.ofDefaults("agent")
        );
        var vip = mock(VipEntitlementService.class);
        when(vip.resolveForUser(userId)).thenReturn(activeEntitlements("SVIP", 9_000));
        runs.setVipEntitlementService(vip);

        RunDtos.RunResponse run = runs.startRun(
                userId, new RunDtos.StartRunRequest(book.getId(), null, "AGENT", "CONTINUE", null, "Continue", "", null)
        );

        assertThat(run.status()).isEqualTo("QUEUED");
        verify(rateLimiter).acquireOrThrow(eq(userId), anyLong(), eq(60L), eq(400_000L));
        verifyNoMoreInteractions(rateLimiter);
    }

    private VipEntitlementService entitlementService() {
        when(redis.opsForValue()).thenReturn(redisValues);
        return new VipEntitlementService(planMapper, subscriptionMapper, redis, objectMapper);
    }

    private String userCacheKey(String version) {
        return VipEntitlementService.REDIS_USER_KEY_PREFIX + version + ":" + userId;
    }

    private UserSubscriptionEntity subscription(String planCode, OffsetDateTime expiresAt) {
        var subscription = new UserSubscriptionEntity();
        subscription.setId(UUID.randomUUID());
        subscription.setUserId(userId);
        subscription.setPlanCode(planCode);
        subscription.setStatus("ACTIVE");
        subscription.setStartedAt(expiresAt.minusDays(30));
        subscription.setExpiresAt(expiresAt);
        return subscription;
    }

    private static VipPlanEntity plan(String code, String displayName, int priceCents, long grantMicros, int discountBps) {
        var plan = new VipPlanEntity();
        plan.setPlanCode(code);
        plan.setDisplayName(displayName);
        plan.setMonthlyPriceCents(priceCents);
        plan.setMonthlyGrantMicros(grantMicros);
        plan.setCreditDiscountBps(discountBps);
        plan.setEnabled(true);
        return plan;
    }

    private static VipDtos.ResolvedUserEntitlements activeEntitlements(String code, int discountBps) {
        return new VipDtos.ResolvedUserEntitlements(
                code, code, "ACTIVE", null, null, discountBps, 0L, VipEntitlementService.defaultEntitlements(code)
        );
    }

    private static List<KnowledgeDocumentEntity> fiveDocuments() {
        return IntStream.range(0, 5).mapToObj(index -> documentWithSize(1_024L)).toList();
    }

    private static KnowledgeDocumentEntity documentWithSize(long fileSize) {
        var doc = new KnowledgeDocumentEntity();
        doc.setId(UUID.randomUUID());
        doc.setFileSize(fileSize);
        return doc;
    }
}
